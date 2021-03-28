package com.coralogix.zio.k8s.client.impl

import _root_.io.circe._
import _root_.io.circe.parser._
import cats.data.NonEmptyList
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

/** Generic implementation of [[Resource]] and [[ResourceDeleteAll]]
  *
  * See https://kubernetes.io/docs/reference/using-api/api-concepts/
  *
  * @param resourceType Kubernetes resource metadata
  * @param cluster Configured Kubernetes cluster
  * @param backend Configured HTTP client
  * @tparam T Resource type, must have JSON encoder and decoder and an implemententation of [[com.coralogix.zio.k8s.client.model.K8sObject]]
  */
final class ResourceClient[
  T: K8sObject: Encoder: Decoder
](
  override protected val resourceType: K8sResourceType,
  override protected val cluster: K8sCluster,
  override protected val backend: SttpBackend[Task, ZioStreams with WebSockets]
) extends Resource[T] with ResourceDeleteAll[T] with ResourceClientBase {

  def getAll(
    namespace: Option[K8sNamespace],
    chunkSize: Int,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None,
    resourceVersion: ListResourceVersion = ListResourceVersion.MostRecent
  ): Stream[K8sFailure, T] =
    ZStream.unwrap {
      handleFailures("getAll") {
        k8sRequest
          .get(
            paginated(
              namespace,
              chunkSize,
              continueToken = None,
              fieldSelector,
              labelSelector,
              resourceVersion
            )
          )
          .response(asJsonAccumulating[ObjectList[T]])
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
                                                          lst <- handleFailures("getAll") {
                                                                   k8sRequest
                                                                     .get(
                                                                       paginated(
                                                                         namespace,
                                                                         chunkSize,
                                                                         continueToken = Some(token),
                                                                         fieldSelector,
                                                                         labelSelector,
                                                                         resourceVersion
                                                                       )
                                                                     )
                                                                     .response(asJsonAccumulating[ObjectList[T]])
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
    Either[ResponseException[String, NonEmptyList[Error]], ZioStreams.BinaryStream],
    ZioStreams
  ] =
    asEither(
      asStringAlways.mapWithMetadata { case (body, meta) => HttpError(body, meta.code) },
      asStreamAlwaysUnsafe(ZioStreams)
    )

  private def watchStream(
    namespace: Option[K8sNamespace],
    fieldSelector: Option[FieldSelector],
    labelSelector: Option[LabelSelector],
    resourceVersion: Option[String]
  ): Stream[K8sFailure, ParsedWatchEvent[T]] = {
    val reqInfo = K8sRequestInfo(resourceType, "watch")
    ZStream
      .unwrap {
        handleFailures("watch") {
          k8sRequest
            .get(watching(namespace, resourceVersion, fieldSelector, labelSelector))
            .response(asStreamUnsafeWithError)
            .readTimeout(10.minutes.asScala)
            .send(backend)
        }.map(_.mapError(RequestFailure(reqInfo, _)))
      }
      .transduce(ZTransducer.utf8Decode >>> ZTransducer.splitLines)
      .mapM { line =>
        for {
          rawEvent <-
            ZIO
              .fromEither(decode[WatchEvent](line))
              .mapError(DeserializationFailure.single(reqInfo, _))
          event    <- ParsedWatchEvent.from[T](reqInfo, rawEvent)
        } yield event
      }
  }

  override def watch(
    namespace: Option[K8sNamespace],
    resourceVersion: Option[String],
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): ZStream[Any, K8sFailure, TypedWatchEvent[T]] =
    ZStream.unwrap {
      Ref.make(resourceVersion).map { lastResourceVersion =>
        ZStream
          .fromEffect(lastResourceVersion.get)
          .flatMap(watchStream(namespace, fieldSelector, labelSelector, _))
          .tap {
            case ParsedTypedWatchEvent(event)    => lastResourceVersion.set(event.resourceVersion)
            case ParsedBookmark(resourceVersion) => lastResourceVersion.set(Some(resourceVersion))
          }
          .collect { case ParsedTypedWatchEvent(event) =>
            event
          }
          .forever
      }
    }

  def get(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T] =
    handleFailures("get") {
      k8sRequest
        .get(simple(Some(name), subresource = None, namespace))
        .response(asJsonAccumulating[T])
        .send(backend)
    }

  override def create(
    newResource: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] =
    handleFailures("create") {
      k8sRequest
        .post(creating(namespace, dryRun))
        .body(newResource)
        .response(asJsonAccumulating[T])
        .send(backend)
    }

  override def replace(
    name: String,
    updatedResource: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] =
    handleFailures("replace") {
      k8sRequest
        .put(modifying(name = name, subresource = None, namespace, dryRun))
        .body(updatedResource)
        .response(asJsonAccumulating[T])
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
    handleFailures("delete") {
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
        .response(asJsonAccumulating[Status])
        .send(backend)
    }

  def deleteAll(
    deleteOptions: DeleteOptions,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): IO[K8sFailure, Status] =
    handleFailures("deleteAll") {
      k8sRequest
        .delete(
          deletingMany(
            namespace,
            dryRun,
            gracePeriod,
            propagationPolicy,
            fieldSelector,
            labelSelector
          )
        )
        .body(deleteOptions)
        .response(asJsonAccumulating[Status])
        .send(backend)
    }
}

object ResourceClient {

  /** Generic resource accessor functions for namespaced resources
    */
  object namespaced {

    /** A paginated query of all resources with filtering possibilities
      * @param namespace Constraint the query to a given namespace. If None, results returned from all namespaces.
      * @param chunkSize Number of items to return per HTTP request
      * @param fieldSelector Constrain the returned items by field selectors. Not all fields are supported by the server.
      * @param labelSelector Constrain the returned items by label selectors.
      * @param resourceVersion Control the returned resources' version.
      * @return A stream of resources
      */
    def getAll[T: Tag](
      namespace: Option[K8sNamespace],
      chunkSize: Int = 10,
      fieldSelector: Option[FieldSelector] = None,
      labelSelector: Option[LabelSelector] = None,
      resourceVersion: ListResourceVersion = ListResourceVersion.MostRecent
    ): ZStream[Has[NamespacedResource[T]], K8sFailure, T] =
      ZStream.accessStream(
        _.get.getAll(namespace, chunkSize, fieldSelector, labelSelector, resourceVersion)
      )

    /** Watch stream of resource change events of type [[com.coralogix.zio.k8s.client.model.TypedWatchEvent]]
      *
      * This function requires the user to control the starting resourceVersion and to
      * restart the watch stream when the server closes the connection.
      *
      * For a more convenient variant check [[watchForever]].
      *
      * @param namespace Constraint the watched resources by their namespace. If None, all namespaces will be watched.
      * @param resourceVersion Last known resource version
      * @param fieldSelector Constrain the returned items by field selectors. Not all fields are supported by the server.
      * @param labelSelector Constrain the returned items by label selectors.
      * @return A stream of watch events
      */
    def watch[T: Tag](
      namespace: Option[K8sNamespace],
      resourceVersion: Option[String],
      fieldSelector: Option[FieldSelector] = None,
      labelSelector: Option[LabelSelector] = None
    ): ZStream[Has[NamespacedResource[T]], K8sFailure, TypedWatchEvent[T]] =
      ZStream.accessStream(_.get.watch(namespace, resourceVersion, fieldSelector, labelSelector))

    /** Infinite watch stream of resource change events of type [[com.coralogix.zio.k8s.client.model.TypedWatchEvent]]
      *
      * The underlying implementation takes advantage of Kubernetes watch bookmarks.
      *
      * @param namespace Constraint the watched resources by their namespace. If None, all namespaces will be watched.
      * @param fieldSelector Constrain the returned items by field selectors. Not all fields are supported by the server.
      * @param labelSelector Constrain the returned items by label selectors.
      * @return A stream of watch events
      */
    def watchForever[T: Tag](
      namespace: Option[K8sNamespace],
      fieldSelector: Option[FieldSelector] = None,
      labelSelector: Option[LabelSelector] = None
    ): ZStream[Has[NamespacedResource[T]] with Clock, K8sFailure, TypedWatchEvent[
      T
    ]] =
      ZStream.accessStream(_.get.watchForever(namespace, fieldSelector, labelSelector))

    /** Get a resource by its name
      * @param name Name of the resource
      * @param namespace Namespace of the resource
      * @return Returns the current version of the resource
      */
    def get[T: Tag](
      name: String,
      namespace: K8sNamespace
    ): ZIO[Has[NamespacedResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.get(name, namespace))

    /** Creates a new resource
      * @param newResource The new resource to define in the cluster.
      * @param namespace Namespace of the resource.
      * @param dryRun If true, the request is sent to the server but it will not create the resource.
      * @return Returns the created resource as it was returned from Kubernetes
      */
    def create[T: Tag](
      newResource: T,
      namespace: K8sNamespace,
      dryRun: Boolean = false
    ): ZIO[Has[NamespacedResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.create(newResource, namespace, dryRun))

    /** Replaces an existing resource selected by its name
      * @param name Name of the resource
      * @param updatedResource The new value of the resource
      * @param namespace Namespace of the resource
      * @param dryRun If true, the request is sent to the server but it will not create the resource.
      * @return Returns the updated resource as it was returned from Kubernetes
      */
    def replace[T: Tag](
      name: String,
      updatedResource: T,
      namespace: K8sNamespace,
      dryRun: Boolean = false
    ): ZIO[Has[NamespacedResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.replace(name, updatedResource, namespace, dryRun))

    /** Replaces the status of a resource that was previously get from server.
      *
      * Use either [[getStatus]] or [[NamespacedResource.get]] to retrieve a value of the resource by name, and then
      * call this method to update its status.
      *
      * @param of The resource object to manipulate
      * @param updatedStatus Updated status value
      * @param namespace Namespace of the resource
      * @param dryRun If true, the request is sent to the server but it will not create the resource.
      * @return Returns the updated resource (not just the status)
      */
    def replaceStatus[StatusT: Tag, T: Tag](
      of: T,
      updatedStatus: StatusT,
      namespace: K8sNamespace,
      dryRun: Boolean = false
    ): ZIO[Has[NamespacedResourceStatus[StatusT, T]], K8sFailure, T] =
      ZIO.accessM(_.get.replaceStatus(of, updatedStatus, namespace, dryRun))

    /** Get the status of a given subresource by name
      * @param name Name of the resource
      * @param namespace Namespace of the resource
      * @return Returns the full resource object but with possibly the non-status fields absent.
      */
    def getStatus[StatusT: Tag, T: Tag](
      name: String,
      namespace: K8sNamespace
    ): ZIO[Has[NamespacedResourceStatus[StatusT, T]], K8sFailure, T] =
      ZIO.accessM(_.get.getStatus(name, namespace))

    /** Deletes an existing resource selected by its name
      * @param name Name of the resource
      * @param deleteOptions Delete options
      * @param namespace Namespace of the resource
      * @param dryRun If true, the request is sent to the server but it will not create the resource.
      * @param gracePeriod The duration in seconds before the object should be deleted. Value must be non-negative integer. The value zero indicates delete immediately. If this value is nil, the default grace period for the specified type will be used. Defaults to a per object value if not specified. zero means delete immediately.
      * @param propagationPolicy Whether and how garbage collection will be performed. Either this field or OrphanDependents may be set, but not both. The default policy is decided by the existing finalizer set in the metadata.finalizers and the resource-specific default policy. Acceptable values are: 'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the dependents in the background; 'Foreground' - a cascading policy that deletes all dependents in the foreground.
      * @return Response from the Kubernetes API
      */
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

    /** Delete all resources matching the provided constraints
      *
      * @param deleteOptions Delete options
      * @param namespace Namespace of the resources to be deleted
      * @param dryRun If true, the request is sent to the server but it will not create the resource.
      * @param gracePeriod The duration in seconds before the object should be deleted. Value must be non-negative integer. The value zero indicates delete immediately. If this value is nil, the default grace period for the specified type will be used. Defaults to a per object value if not specified. zero means delete immediately.
      * @param propagationPolicy Whether and how garbage collection will be performed. Either this field or OrphanDependents may be set, but not both. The default policy is decided by the existing finalizer set in the metadata.finalizers and the resource-specific default policy. Acceptable values are: 'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the dependents in the background; 'Foreground' - a cascading policy that deletes all dependents in the foreground.
      * @param fieldSelector Select the items to be deleted by field selectors. Not all fields are supported by the server.
      * @param labelSelector Select the items to be deleted by label selectors.
      * @return Status returned by the Kubernetes API
      */
    def deleteAll[T: Tag](
      deleteOptions: DeleteOptions,
      namespace: K8sNamespace,
      dryRun: Boolean = false,
      gracePeriod: Option[Duration] = None,
      propagationPolicy: Option[PropagationPolicy] = None,
      fieldSelector: Option[FieldSelector] = None,
      labelSelector: Option[LabelSelector] = None
    ): ZIO[Has[NamespacedResourceDeleteAll[T]], K8sFailure, Status] =
      ZIO.accessM(
        _.get.deleteAll(
          deleteOptions,
          namespace,
          dryRun,
          gracePeriod,
          propagationPolicy,
          fieldSelector,
          labelSelector
        )
      )
  }

  /** Generic resource accessor functions for cluster resources
    */
  object cluster {

    /** A paginated query of all resources with filtering possibilities
      * @param chunkSize Number of items to return per HTTP request
      * @param fieldSelector Constrain the returned items by field selectors. Not all fields are supported by the server.
      * @param labelSelector Constrain the returned items by label selectors.
      * @param resourceVersion Control the returned resources' version.
      * @return A stream of resources
      */
    def getAll[T: Tag](
      chunkSize: Int = 10,
      fieldSelector: Option[FieldSelector] = None,
      labelSelector: Option[LabelSelector] = None,
      resourceVersion: ListResourceVersion = ListResourceVersion.MostRecent
    ): ZStream[Has[ClusterResource[T]], K8sFailure, T] =
      ZStream.accessStream(_.get.getAll(chunkSize, fieldSelector, labelSelector, resourceVersion))

    /** Watch stream of resource change events of type [[com.coralogix.zio.k8s.client.model.TypedWatchEvent]]
      *
      * This function requires the user to control the starting resourceVersion and to
      * restart the watch stream when the server closes the connection.
      *
      * For a more convenient variant check [[watchForever]].
      *
      * @param resourceVersion Last known resource version
      * @param fieldSelector Constrain the returned items by field selectors. Not all fields are supported by the server.
      * @param labelSelector Constrain the returned items by label selectors.
      * @return A stream of watch events
      */
    def watch[T: Tag](
      resourceVersion: Option[String],
      fieldSelector: Option[FieldSelector] = None,
      labelSelector: Option[LabelSelector] = None
    ): ZStream[Has[ClusterResource[T]], K8sFailure, TypedWatchEvent[T]] =
      ZStream.accessStream(_.get.watch(resourceVersion, fieldSelector, labelSelector))

    /** Infinite watch stream of resource change events of type [[com.coralogix.zio.k8s.client.model.TypedWatchEvent]]
      *
      * The underlying implementation takes advantage of Kubernetes watch bookmarks.
      *
      * @param fieldSelector Constrain the returned items by field selectors. Not all fields are supported by the server.
      * @param labelSelector Constrain the returned items by label selectors.
      * @return A stream of watch events
      */
    def watchForever[T: Tag](
      fieldSelector: Option[FieldSelector] = None,
      labelSelector: Option[LabelSelector] = None
    ): ZStream[Has[ClusterResource[T]] with Clock, K8sFailure, TypedWatchEvent[T]] =
      ZStream.accessStream(_.get.watchForever(fieldSelector, labelSelector))

    /** Get a resource by its name
      * @param name Name of the resource
      * @return Returns the current version of the resource
      */
    def get[T: Tag](
      name: String
    ): ZIO[Has[ClusterResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.get(name))

    /** Creates a new resource
      * @param newResource The new resource to define in the cluster.
      * @param dryRun If true, the request is sent to the server but it will not create the resource.
      * @return Returns the created resource as it was returned from Kubernetes
      */
    def create[T: Tag](
      newResource: T,
      dryRun: Boolean = false
    ): ZIO[Has[ClusterResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.create(newResource, dryRun))

    /** Replaces an existing resource selected by its name
      * @param name Name of the resource
      * @param updatedResource The new value of the resource
      * @param dryRun If true, the request is sent to the server but it will not create the resource.
      * @return Returns the updated resource as it was returned from Kubernetes
      */
    def replace[T: Tag](
      name: String,
      updatedResource: T,
      dryRun: Boolean = false
    ): ZIO[Has[ClusterResource[T]], K8sFailure, T] =
      ZIO.accessM(_.get.replace(name, updatedResource, dryRun))

    /** Replaces the status of a resource that was previously get from server.
      *
      * Use either [[getStatus]] or [[ClusterResource.get]] to retrieve a value of the resource by name, and then
      * call this method to update its status.
      *
      * @param of The resource object to manipulate
      * @param updatedStatus Updated status value
      * @param dryRun If true, the request is sent to the server but it will not create the resource.
      * @return Returns the updated resource (not just the status)
      */
    def replaceStatus[StatusT: Tag, T: Tag](
      of: T,
      updatedStatus: StatusT,
      dryRun: Boolean = false
    ): ZIO[Has[ClusterResourceStatus[StatusT, T]], K8sFailure, T] =
      ZIO.accessM(_.get.replaceStatus(of, updatedStatus, dryRun))

    /** Get the status of a given subresource by name
      * @param name Name of the resource
      * @return Returns the full resource object but with possibly the non-status fields absent.
      */
    def getStatus[StatusT: Tag, T: Tag](
      name: String
    ): ZIO[Has[ClusterResourceStatus[StatusT, T]], K8sFailure, T] =
      ZIO.accessM(_.get.getStatus(name))

    /** Deletes an existing resource selected by its name
      * @param name Name of the resource
      * @param deleteOptions Delete options
      * @param dryRun If true, the request is sent to the server but it will not create the resource.
      * @param gracePeriod The duration in seconds before the object should be deleted. Value must be non-negative integer. The value zero indicates delete immediately. If this value is nil, the default grace period for the specified type will be used. Defaults to a per object value if not specified. zero means delete immediately.
      * @param propagationPolicy Whether and how garbage collection will be performed. Either this field or OrphanDependents may be set, but not both. The default policy is decided by the existing finalizer set in the metadata.finalizers and the resource-specific default policy. Acceptable values are: 'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the dependents in the background; 'Foreground' - a cascading policy that deletes all dependents in the foreground.
      * @return Response from the Kubernetes API
      */
    def delete[T: Tag](
      name: String,
      deleteOptions: DeleteOptions,
      dryRun: Boolean = false,
      gracePeriod: Option[Duration] = None,
      propagationPolicy: Option[PropagationPolicy] = None
    ): ZIO[Has[ClusterResource[T]], K8sFailure, Status] =
      ZIO.accessM(_.get.delete(name, deleteOptions, dryRun, gracePeriod, propagationPolicy))

    /** Delete all resources matching the provided constraints
      *
      * @param deleteOptions Delete options
      * @param dryRun If true, the request is sent to the server but it will not create the resource.
      * @param gracePeriod The duration in seconds before the object should be deleted. Value must be non-negative integer. The value zero indicates delete immediately. If this value is nil, the default grace period for the specified type will be used. Defaults to a per object value if not specified. zero means delete immediately.
      * @param propagationPolicy Whether and how garbage collection will be performed. Either this field or OrphanDependents may be set, but not both. The default policy is decided by the existing finalizer set in the metadata.finalizers and the resource-specific default policy. Acceptable values are: 'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the dependents in the background; 'Foreground' - a cascading policy that deletes all dependents in the foreground.
      * @param fieldSelector Select the items to be deleted by field selectors. Not all fields are supported by the server.
      * @param labelSelector Select the items to be deleted by label selectors.
      * @return Status returned by the Kubernetes API
      */
    def deleteAll[T: Tag](
      deleteOptions: DeleteOptions,
      dryRun: Boolean = false,
      gracePeriod: Option[Duration] = None,
      propagationPolicy: Option[PropagationPolicy] = None,
      fieldSelector: Option[FieldSelector] = None,
      labelSelector: Option[LabelSelector] = None
    ): ZIO[Has[ClusterResourceDeleteAll[T]], K8sFailure, Status] =
      ZIO.accessM(
        _.get.deleteAll(
          deleteOptions,
          dryRun,
          gracePeriod,
          propagationPolicy,
          fieldSelector,
          labelSelector
        )
      )
  }
}
