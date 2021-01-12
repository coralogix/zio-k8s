package com.coralogix.zio.k8s.client

import _root_.io.circe._
import _root_.io.circe.parser._
import _root_.io.circe.syntax._
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, Status, WatchEvent }
import sttp.capabilities.zio.ZioStreams
import sttp.client3._
import sttp.client3.circe._
import sttp.client3.httpclient.zio._
import sttp.model.{ StatusCode, Uri }
import zio._
import zio.clock.Clock
import zio.config.ZConfig
import zio.duration._
import com.coralogix.zio.k8s.client.internal.ZStreamOps
import com.coralogix.zio.k8s.client.model._
import zio.stream._

trait Resource[T <: Object] {
  def getAll(namespace: Option[K8sNamespace], chunkSize: Int = 10): Stream[K8sFailure, T]

  def watch(
    namespace: Option[K8sNamespace],
    resourceVersion: Option[String]
  ): Stream[K8sFailure, TypedWatchEvent[T]]

  def watchForever(
    namespace: Option[K8sNamespace]
  ): ZStream[Clock, K8sFailure, TypedWatchEvent[T]] =
    ZStream.succeed(Reseted) ++ watch(namespace, None)
      .retry(Schedule.recurWhileEquals(Gone))

  def get(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T]

  def create(
    newResource: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false
  ): IO[K8sFailure, T]

  def replace(
    name: String,
    updatedResource: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false
  ): IO[K8sFailure, T]

  def delete(
    name: String,
    deleteOptions: DeleteOptions,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false
  ): IO[K8sFailure, Status]
}

trait ResourceStatus[StatusT, T <: Object] {
  def replaceStatus(
    of: T,
    updatedStatus: StatusT,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false
  ): IO[K8sFailure, T]
}

class NamespacedResource[T <: Object](impl: Resource[T]) {
  def getAll(namespace: Option[K8sNamespace], chunkSize: Int = 10): Stream[K8sFailure, T] =
    impl.getAll(namespace, chunkSize)

  def watch(
    namespace: Option[K8sNamespace],
    resourceVersion: Option[String]
  ): Stream[K8sFailure, TypedWatchEvent[T]] =
    impl.watch(namespace, resourceVersion)

  def watchForever(
    namespace: Option[K8sNamespace]
  ): ZStream[Clock, K8sFailure, TypedWatchEvent[T]] =
    impl.watchForever(namespace)

  def get(name: String, namespace: K8sNamespace): IO[K8sFailure, T] =
    impl.get(name, Some(namespace))

  def create(newResource: T, namespace: K8sNamespace, dryRun: Boolean = false): IO[K8sFailure, T] =
    impl.create(newResource, Some(namespace), dryRun)

  def replace(
    name: String,
    updatedResource: T,
    namespace: K8sNamespace,
    dryRun: Boolean = false
  ): IO[K8sFailure, T] =
    impl.replace(name, updatedResource, Some(namespace), dryRun)

  def delete(
    name: String,
    deleteOptions: DeleteOptions,
    namespace: K8sNamespace,
    dryRun: Boolean = false
  ): IO[K8sFailure, Status] =
    impl.delete(name, deleteOptions, Some(namespace), dryRun)
}

class NamespacedResourceStatus[StatusT, T <: Object](impl: ResourceStatus[StatusT, T]) {
  def replaceStatus(
    of: T,
    updatedResource: StatusT,
    namespace: K8sNamespace,
    dryRun: Boolean = false
  ): IO[K8sFailure, T] =
    impl.replaceStatus(of, updatedResource, Some(namespace), dryRun)
}

class ClusterResource[T <: Object](
  impl: Resource[T]
) {
  def getAll(chunkSize: Int = 10): Stream[K8sFailure, T] =
    impl.getAll(None, chunkSize)

  def watch(resourceVersion: Option[String]): Stream[K8sFailure, TypedWatchEvent[T]] =
    impl.watch(None, resourceVersion)

  def watchForever(): ZStream[Clock, K8sFailure, TypedWatchEvent[T]] =
    impl.watchForever(None)

  def get(name: String): IO[K8sFailure, T] =
    impl.get(name, None)

  def create(newResource: T, dryRun: Boolean = false): IO[K8sFailure, T] =
    impl.create(newResource, None, dryRun)

  def replace(
    name: String,
    updatedResource: T,
    dryRun: Boolean = false
  ): IO[K8sFailure, T] =
    impl.replace(name, updatedResource, None, dryRun)

  def delete(
    name: String,
    deleteOptions: DeleteOptions,
    dryRun: Boolean = false
  ): IO[K8sFailure, Status] =
    impl.delete(name, deleteOptions, None, dryRun)
}

class ClusterResourceStatus[StatusT, T <: Object](impl: ResourceStatus[StatusT, T]) {
  def replaceStatus(
    of: T,
    updatedStatus: StatusT,
    dryRun: Boolean = false
  ): IO[K8sFailure, T] =
    impl.replaceStatus(of, updatedStatus, None, dryRun)
}

trait ResourceClientBase {
  protected val resourceType: K8sResourceType
  protected val cluster: K8sCluster
  protected val backend: SttpClient.Service

  protected val k8sRequest: RequestT[Empty, Either[String, String], Any] =
    basicRequest.auth.bearer(cluster.token)

  protected def simple(name: Option[String], namespace: Option[K8sNamespace]): Uri =
    K8sSimpleUri(resourceType, name, namespace).toUri(cluster)

  protected def creating(namespace: Option[K8sNamespace], dryRun: Boolean): Uri =
    K8sCreatorUri(resourceType, namespace, dryRun).toUri(cluster)

  protected def modifying(name: String, namespace: Option[K8sNamespace], dryRun: Boolean): Uri =
    K8sModifierUri(resourceType, name, namespace, dryRun).toUri(cluster)

  protected def modifyingStatus(
    name: String,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): Uri =
    K8sStatusModifierUri(resourceType, name, namespace, dryRun).toUri(cluster)

  protected def paginated(
    namespace: Option[K8sNamespace],
    limit: Int,
    continueToken: Option[String]
  ): Uri =
    K8sPaginatedUri(resourceType, namespace, limit, continueToken).toUri(cluster)

  protected def watching(namespace: Option[K8sNamespace], resourceVersion: Option[String]): Uri =
    K8sWatchUri(resourceType, namespace, resourceVersion).toUri(cluster)

  protected def handleFailures[A](
    f: Task[Response[Either[ResponseException[String, Error], A]]]
  ): IO[K8sFailure, A] =
    f
      .mapError(RequestFailure.apply)
      .flatMap { response =>
        response.body match {
          case Left(HttpError(error, StatusCode.Unauthorized)) =>
            IO.fail(Unauthorized(error))
          case Left(HttpError(error, StatusCode.Gone)) =>
            IO.fail(Gone)
          case Left(HttpError(error, StatusCode.NotFound)) =>
            IO.fail(NotFound)
          case Left(HttpError(error, code)) =>
            decode[Status](error) match {
              case Left(_) =>
                IO.fail(HttpFailure(error, code))
              case Right(status) =>
                IO.fail(DecodedFailure(status, code))
            }
          case Left(DeserializationException(_, error)) =>
            IO.fail(DeserializationFailure.single(error))
          case Right(value) =>
            IO.succeed(value)
        }
      }
}

class ResourceClient[
  T <: Object: Encoder: Decoder
] private[client] (
  override protected val resourceType: K8sResourceType,
  override protected val cluster: K8sCluster,
  override protected val backend: SttpClient.Service
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
            pull = for {
                     continueToken <- nextContinueToken.get
                     chunk <- continueToken match {
                                case Some("") | None =>
                                  ZIO.fail(None)
                                case Some(token) =>
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
                                    _ <- nextContinueToken.set(lst.metadata.flatMap(_.continue))
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
          parsedEvent <- ZIO
                           .fromEither(decode[WatchEvent](line))
                           .mapError(DeserializationFailure.single)
          event <- TypedWatchEvent.from[T](parsedEvent)
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
        .get(simple(Some(name), namespace))
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
        .put(modifying(name = name, namespace, dryRun))
        .body(updatedResource)
        .response(asJson[T])
        .send(backend)
    }

  override def delete(
    name: String,
    deleteOptions: DeleteOptions,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, Status] =
    handleFailures {
      k8sRequest
        .delete(modifying(name = name, namespace, dryRun))
        .body(deleteOptions)
        .response(asJson[Status])
        .send(backend)
    }
}

class ResourceClientStatus[StatusT: Encoder, T <: Object: Encoder: Decoder] private[client] (
  override protected val resourceType: K8sResourceType,
  override protected val cluster: K8sCluster,
  override protected val backend: SttpClient.Service
) extends ResourceStatus[StatusT, T] with ResourceClientBase {

  override def replaceStatus(
    of: T,
    updatedStatus: StatusT,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] =
    for {
      name <- of.getName
      response <- handleFailures {
                    k8sRequest
                      .put(modifyingStatus(name = name, namespace, dryRun))
                      .body(toStatusUpdate(of, updatedStatus))
                      .response(asJson[T])
                      .send(backend)
                  }
    } yield response

  private def toStatusUpdate(of: T, newStatus: StatusT): Json =
    of.asJson.mapObject(
      _.remove("spec").add("status", newStatus.asJson)
    )
}

object ResourceClient {
  object namespaced {
    def liveWithoutStatus[T <: Object: Encoder: Decoder: Tag](
      resourceType: K8sResourceType
    ): ZLayer[SttpClient with ZConfig[K8sCluster], Nothing, Has[NamespacedResource[T]]] =
      ZLayer.fromServices[SttpClient.Service, K8sCluster, NamespacedResource[T]] {
        (backend: SttpClient.Service, cluster: K8sCluster) =>
          new NamespacedResource(new ResourceClient[T](resourceType, cluster, backend))
      }

    def liveWithStatus[StatusT: Encoder: Tag, T <: Object: Encoder: Decoder: Tag](
      resourceType: K8sResourceType
    ): ZLayer[SttpClient with ZConfig[K8sCluster], Nothing, Has[
      NamespacedResourceStatus[StatusT, T]
    ] with Has[NamespacedResource[T]]] =
      ZLayer.fromServices[SttpClient.Service, K8sCluster, NamespacedResourceStatus[StatusT, T]] {
        (backend: SttpClient.Service, cluster: K8sCluster) =>
          new NamespacedResourceStatus(
            new ResourceClientStatus[StatusT, T](resourceType, cluster, backend)
          )
      } ++ liveWithoutStatus(resourceType)

    def getAll[T <: Object: Tag](
      namespace: Option[K8sNamespace],
      chunkSize: Int = 10
    ): ZStream[Has[NamespacedResource[T]], K8sFailure, T] =
      ZStream.accessStream(_.get.getAll(namespace, chunkSize))

    def watch[T <: Object: Tag](
      namespace: Option[K8sNamespace],
      resourceVersion: Option[String]
    ): ZStream[Has[NamespacedResource[T]], K8sFailure, TypedWatchEvent[T]] =
      ZStream.accessStream(_.get.watch(namespace, resourceVersion))

    def watchForever[T <: Object: Tag](
      namespace: Option[K8sNamespace]
    ): ZStream[Has[NamespacedResource[T]] with Clock, K8sFailure, TypedWatchEvent[
      T
    ]] =
      ZStream.accessStream(_.get.watchForever(namespace))

    def get[T <: Object: Tag](
      name: String,
      namespace: K8sNamespace
    ): ZIO[Has[NamespacedResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.get(name, namespace))

    def create[T <: Object: Tag](
      newResource: T,
      namespace: K8sNamespace,
      dryRun: Boolean = false
    ): ZIO[Has[NamespacedResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.create(newResource, namespace, dryRun))

    def replace[T <: Object: Tag](
      name: String,
      updatedResource: T,
      namespace: K8sNamespace,
      dryRun: Boolean = false
    ): ZIO[Has[NamespacedResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.replace(name, updatedResource, namespace, dryRun))

    def replaceStatus[StatusT: Tag, T <: Object: Tag](
      of: T,
      updatedStatus: StatusT,
      namespace: K8sNamespace,
      dryRun: Boolean = false
    ): ZIO[Has[NamespacedResourceStatus[StatusT, T]], K8sFailure, T] =
      ZIO.accessM(_.get.replaceStatus(of, updatedStatus, namespace, dryRun))

    def delete[T <: Object: Tag](
      name: String,
      deleteOptions: DeleteOptions,
      namespace: K8sNamespace,
      dryRun: Boolean = false
    ): ZIO[Has[NamespacedResource[T]], K8sFailure, Status] =
      ZIO.accessM(_.get.delete(name, deleteOptions, namespace, dryRun))
  }

  object cluster {
    def liveWithoutStatus[T <: Object: Encoder: Decoder: Tag](
      resourceType: K8sResourceType
    ): ZLayer[SttpClient with ZConfig[K8sCluster], Nothing, Has[
      ClusterResource[T]
    ]] =
      ZLayer.fromServices[SttpClient.Service, K8sCluster, ClusterResource[T]] {
        (backend: SttpClient.Service, cluster: K8sCluster) =>
          new ClusterResource[T](
            new ResourceClient[T](resourceType, cluster, backend)
          )
      }

    def liveWithStatus[StatusT: Tag: Encoder, T <: Object: Encoder: Decoder: Tag](
      resourceType: K8sResourceType
    ): ZLayer[SttpClient with ZConfig[K8sCluster], Nothing, Has[
      ClusterResourceStatus[StatusT, T]
    ] with Has[ClusterResource[T]]] =
      ZLayer.fromServices[SttpClient.Service, K8sCluster, ClusterResourceStatus[StatusT, T]] {
        (backend: SttpClient.Service, cluster: K8sCluster) =>
          new ClusterResourceStatus[StatusT, T](
            new ResourceClientStatus[StatusT, T](resourceType, cluster, backend)
          )
      } ++ liveWithoutStatus(resourceType)

    def getAll[T <: Object: Tag](
      chunkSize: Int = 10
    ): ZStream[Has[ClusterResource[T]], K8sFailure, T] =
      ZStream.accessStream(_.get.getAll(chunkSize))

    def watch[T <: Object: Tag](
      resourceVersion: Option[String]
    ): ZStream[Has[ClusterResource[T]], K8sFailure, TypedWatchEvent[T]] =
      ZStream.accessStream(_.get.watch(resourceVersion))

    def watchForever[T <: Object: Tag](
    ): ZStream[Has[ClusterResource[T]] with Clock, K8sFailure, TypedWatchEvent[T]] =
      ZStream.accessStream(_.get.watchForever())

    def get[T <: Object: Tag](
      name: String
    ): ZIO[Has[ClusterResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.get(name))

    def create[T <: Object: Tag](
      newResource: T,
      dryRun: Boolean = false
    ): ZIO[Has[ClusterResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.create(newResource, dryRun))

    def replace[T <: Object: Tag](
      name: String,
      updatedResource: T,
      dryRun: Boolean = false
    ): ZIO[Has[ClusterResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.replace(name, updatedResource, dryRun))

    def replaceStatus[StatusT: Tag, T <: Object: Tag](
      of: T,
      updatedStatus: StatusT,
      dryRun: Boolean = false
    ): ZIO[Has[ClusterResourceStatus[StatusT, T]], K8sFailure, T] =
      ZIO.accessM(_.get.replaceStatus(of, updatedStatus, dryRun))

    def delete[T <: Object: Tag](
      name: String,
      deleteOptions: DeleteOptions,
      dryRun: Boolean = false
    ): ZIO[Has[ClusterResource[T]], K8sFailure, Status] =
      ZIO.accessM(_.get.delete(name, deleteOptions, dryRun))
  }
}
