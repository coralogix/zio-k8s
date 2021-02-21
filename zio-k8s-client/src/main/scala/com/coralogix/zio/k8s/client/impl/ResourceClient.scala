package com.coralogix.zio.k8s.client.impl

import _root_.io.circe._
import _root_.io.circe.parser._
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.client._
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, Status, WatchEvent }
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3._
import sttp.client3.circe._
import zio._
import zio.clock.Clock
import zio.duration._
import zio.stream._

final class ResourceClient[
  T: K8sObject: Encoder: Decoder
](
  override protected val resourceType: K8sResourceType,
  override protected val cluster: K8sCluster,
  override protected val backend: SttpBackend[Task, ZioStreams with WebSockets]
) extends Resource[T] with ResourceClientBase {

  // See https://kubernetes.io/docs/reference/using-api/api-concepts/

  // TODO: error-accumulating json unmarshallers instead of asJson

  def getAll(namespace: Option[K8sNamespace], chunkSize: Int): Stream[K8sFailure, T] =
    ZStream.unwrap {
      handleFailures {
        k8sRequest
          .get(paginated(namespace, chunkSize, continueToken = None))
          .response(asJson[ObjectList[T]])
          .send(backend)
      }.map { initialResponse =>
        val rest = ZStream {
          for {
            nextContinueToken <- Ref.make(initialResponse.metadata.flatMap(_.continue)).toManaged_
            pull               = for {
                                   continueToken <- nextContinueToken.get
                                   chunk         <- continueToken match {
                                                      case Optional.Present("") | Optional.Absent =>
                                                        ZIO.fail(None)
                                                      case Optional.Present(token)                =>
                                                        for {
                                                          lst <- handleFailures {
                                                                   k8sRequest
                                                                     .get(
                                                                       paginated(
                                                                         namespace,
                                                                         chunkSize,
                                                                         continueToken = Some(token)
                                                                       )
                                                                     )
                                                                     .response(asJson[ObjectList[T]])
                                                                     .send(backend)
                                                                 }.mapError(Some.apply)
                                                          _   <- nextContinueToken.set(lst.metadata.flatMap(_.continue))
                                                        } yield Chunk.fromIterable(lst.items)
                                                    }
                                 } yield chunk
          } yield pull
        }
        ZStream.fromIterable(initialResponse.items).concat(rest)
      }
    }

  private def asStreamUnsafeWithError: ResponseAs[
    Either[ResponseException[String, Error], ZioStreams.BinaryStream],
    ZioStreams
  ] =
    asEither(
      asStringAlways.mapWithMetadata { case (body, meta) => HttpError(body, meta.code) },
      asStreamAlwaysUnsafe(ZioStreams)
    )

  private def watchStream(
    namespace: Option[K8sNamespace],
    resourceVersion: Option[String]
  ): Stream[K8sFailure, TypedWatchEvent[T]] =
    ZStream
      .unwrap {
        handleFailures {
          k8sRequest
            .get(watching(namespace, resourceVersion))
            .response(asStreamUnsafeWithError)
            .readTimeout(10.minutes.asScala)
            .send(backend)
        }.map(_.mapError(RequestFailure))
      }
      .transduce(ZTransducer.utf8Decode >>> ZTransducer.splitLines)
      .mapM { line =>
        for {
          parsedEvent <-
            ZIO
              .fromEither(decode[WatchEvent](line))
              .mapError(DeserializationFailure.single)
          event       <- TypedWatchEvent.from[T](parsedEvent)
        } yield event
      }

  override def watch(
    namespace: Option[K8sNamespace],
    resourceVersion: Option[String]
  ): ZStream[Any, K8sFailure, TypedWatchEvent[T]] =
    ZStream.unwrap {
      Ref.make(resourceVersion).map { lastResourceVersion =>
        ZStream
          .fromEffect(lastResourceVersion.get)
          .flatMap(watchStream(namespace, _))
          .tap(event => lastResourceVersion.set(event.resourceVersion))
          .forever
      }
    }

  def get(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T] =
    handleFailures {
      k8sRequest
        .get(simple(Some(name), subresource = None, namespace))
        .response(asJson[T])
        .send(backend)
    }

  override def create(
    newResource: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] =
    handleFailures {
      k8sRequest
        .post(creating(namespace, dryRun))
        .body(newResource)
        .response(asJson[T])
        .send(backend)
    }

  override def replace(
    name: String,
    updatedResource: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] =
    handleFailures {
      k8sRequest
        .put(modifying(name = name, subresource = None, namespace, dryRun))
        .body(updatedResource)
        .response(asJson[T])
        .send(backend)
    }

  override def delete(
    name: String,
    deleteOptions: DeleteOptions,
    namespace: Option[K8sNamespace],
    dryRun: Boolean,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None
  ): IO[K8sFailure, Status] =
    handleFailures {
      k8sRequest
        .delete(
          deleting(
            name = name,
            subresource = None,
            namespace,
            dryRun,
            gracePeriod,
            propagationPolicy
          )
        )
        .body(deleteOptions)
        .response(asJson[Status])
        .send(backend)
    }
}

object ResourceClient {
  object namespaced {
    def getAll[T: Tag](
      namespace: Option[K8sNamespace],
      chunkSize: Int = 10
    ): ZStream[Has[NamespacedResource[T]], K8sFailure, T] =
      ZStream.accessStream(_.get.getAll(namespace, chunkSize))

    def watch[T: Tag](
      namespace: Option[K8sNamespace],
      resourceVersion: Option[String]
    ): ZStream[Has[NamespacedResource[T]], K8sFailure, TypedWatchEvent[T]] =
      ZStream.accessStream(_.get.watch(namespace, resourceVersion))

    def watchForever[T: Tag](
      namespace: Option[K8sNamespace]
    ): ZStream[Has[NamespacedResource[T]] with Clock, K8sFailure, TypedWatchEvent[
      T
    ]] =
      ZStream.accessStream(_.get.watchForever(namespace))

    def get[T: Tag](
      name: String,
      namespace: K8sNamespace
    ): ZIO[Has[NamespacedResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.get(name, namespace))

    def create[T: Tag](
      newResource: T,
      namespace: K8sNamespace,
      dryRun: Boolean = false
    ): ZIO[Has[NamespacedResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.create(newResource, namespace, dryRun))

    def replace[T: Tag](
      name: String,
      updatedResource: T,
      namespace: K8sNamespace,
      dryRun: Boolean = false
    ): ZIO[Has[NamespacedResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.replace(name, updatedResource, namespace, dryRun))

    def replaceStatus[StatusT: Tag, T: Tag](
      of: T,
      updatedStatus: StatusT,
      namespace: K8sNamespace,
      dryRun: Boolean = false
    ): ZIO[Has[NamespacedResourceStatus[StatusT, T]], K8sFailure, T] =
      ZIO.accessM(_.get.replaceStatus(of, updatedStatus, namespace, dryRun))

    def getStatus[StatusT: Tag, T: Tag](
      name: String,
      namespace: K8sNamespace
    ): ZIO[Has[NamespacedResourceStatus[StatusT, T]], K8sFailure, T] =
      ZIO.accessM(_.get.getStatus(name, namespace))

    def delete[T: Tag](
      name: String,
      deleteOptions: DeleteOptions,
      namespace: K8sNamespace,
      dryRun: Boolean = false,
      gracePeriod: Option[Duration] = None,
      propagationPolicy: Option[PropagationPolicy] = None
    ): ZIO[Has[NamespacedResource[T]], K8sFailure, Status] =
      ZIO.accessM(
        _.get.delete(name, deleteOptions, namespace, dryRun, gracePeriod, propagationPolicy)
      )
  }

  object cluster {
    def getAll[T: Tag](
      chunkSize: Int = 10
    ): ZStream[Has[ClusterResource[T]], K8sFailure, T] =
      ZStream.accessStream(_.get.getAll(chunkSize))

    def watch[T: Tag](
      resourceVersion: Option[String]
    ): ZStream[Has[ClusterResource[T]], K8sFailure, TypedWatchEvent[T]] =
      ZStream.accessStream(_.get.watch(resourceVersion))

    def watchForever[T: Tag](
    ): ZStream[Has[ClusterResource[T]] with Clock, K8sFailure, TypedWatchEvent[T]] =
      ZStream.accessStream(_.get.watchForever())

    def get[T: Tag](
      name: String
    ): ZIO[Has[ClusterResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.get(name))

    def create[T: Tag](
      newResource: T,
      dryRun: Boolean = false
    ): ZIO[Has[ClusterResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.create(newResource, dryRun))

    def replace[T: Tag](
      name: String,
      updatedResource: T,
      dryRun: Boolean = false
    ): ZIO[Has[ClusterResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.replace(name, updatedResource, dryRun))

    def replaceStatus[StatusT: Tag, T: Tag](
      of: T,
      updatedStatus: StatusT,
      dryRun: Boolean = false
    ): ZIO[Has[ClusterResourceStatus[StatusT, T]], K8sFailure, T] =
      ZIO.accessM(_.get.replaceStatus(of, updatedStatus, dryRun))

    def getStatus[StatusT: Tag, T: Tag](
      name: String
    ): ZIO[Has[ClusterResourceStatus[StatusT, T]], K8sFailure, T] =
      ZIO.accessM(_.get.getStatus(name))

    def delete[T <: Object: Tag](
      name: String,
      deleteOptions: DeleteOptions,
      dryRun: Boolean = false,
      gracePeriod: Option[Duration] = None,
      propagationPolicy: Option[PropagationPolicy] = None
    ): ZIO[Has[ClusterResource[T]], K8sFailure, Status] =
      ZIO.accessM(_.get.delete(name, deleteOptions, dryRun, gracePeriod, propagationPolicy))
  }
}
