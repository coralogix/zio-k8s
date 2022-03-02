package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.{ K8sNamespace, PropagationPolicy }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, Preconditions, Status }
import sttp.model.StatusCode
import zio.Clock
import zio.{ IO, Schedule, ZIO }

import zio._

/** Extra capability for [[Resource]] interfaces providing delete
  *
  * It is separated because because its result type varies for different resources.
  *
  * @tparam T
  *   Resource type
  * @tparam DeleteResult
  *   Delete result type
  */
trait ResourceDelete[T, DeleteResult] {

  /** Deletes an existing resource selected by its name
    *
    * @param name
    *   Name of the resource
    * @param deleteOptions
    *   Delete options
    * @param namespace
    *   Namespace. For namespaced resources it must be Some. For cluster resources, it must be None.
    * @param dryRun
    *   If true, the request is sent to the server but it will not create the resource.
    * @param gracePeriod
    *   The duration in seconds before the object should be deleted. Value must be non-negative
    *   integer. The value zero indicates delete immediately. If this value is nil, the default
    *   grace period for the specified type will be used. Defaults to a per object value if not
    *   specified. zero means delete immediately.
    * @param propagationPolicy
    *   Whether and how garbage collection will be performed. Either this field or OrphanDependents
    *   may be set, but not both. The default policy is decided by the existing finalizer set in the
    *   metadata.finalizers and the resource-specific default policy. Acceptable values are:
    *   'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the
    *   dependents in the background; 'Foreground' - a cascading policy that deletes all dependents
    *   in the foreground.
    * @return
    *   Response from the Kubernetes API
    */
  def delete(
    name: String,
    deleteOptions: DeleteOptions,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None
  ): IO[K8sFailure, DeleteResult]

  /** Deletes an existing resource selected by its name and waits until it has gone
    * @param name
    *   Name of the resource
    * @param deleteOptions
    *   Delete options
    * @param namespace
    *   Namespace. For namespaced resources it must be Some. For cluster resources, it must be None.
    * @param dryRun
    *   If true, the request is sent to the server but it will not create the resource.
    * @param gracePeriod
    *   The duration in seconds before the object should be deleted. Value must be non-negative
    *   integer. The value zero indicates delete immediately. If this value is nil, the default
    *   grace period for the specified type will be used. Defaults to a per object value if not
    *   specified. zero means delete immediately.
    * @param propagationPolicy
    *   Whether and how garbage collection will be performed. Either this field or OrphanDependents
    *   may be set, but not both. The default policy is decided by the existing finalizer set in the
    *   metadata.finalizers and the resource-specific default policy. Acceptable values are:
    *   'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the
    *   dependents in the background; 'Foreground' - a cascading policy that deletes all dependents
    *   in the foreground.
    */
  def deleteAndWait(
    name: String,
    deleteOptions: DeleteOptions,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None
  )(implicit ev: DeleteResult <:< Status): ZIO[Clock, K8sFailure, Unit] =
    delete(name, deleteOptions, namespace, dryRun, gracePeriod, propagationPolicy).flatMap {
      status =>
        for {
          details             <- status.getDetails
          uid                 <- details.getUid
          deleteOptionsWithUid = deleteOptions.copy(
                                   preconditions = deleteOptions.preconditions
                                     .getOrElse(Preconditions())
                                     .copy(
                                       uid = uid
                                     )
                                 )
          _                   <- delete(name, deleteOptionsWithUid, namespace, dryRun, gracePeriod, propagationPolicy)
                                   .as(false)
                                   .catchSome {
                                     case NotFound                                  => ZIO.succeed(true) // Delete completed
                                     case DecodedFailure(_, _, StatusCode.Conflict) =>
                                       ZIO.succeed(true) // Delete completed and a new item with same name was created
                                   }
                                   .repeat(Schedule.fixed(1.second) *> Schedule.recurUntil((done: Boolean) => done))
                                   .unit
        } yield ()
    }
}

trait NamespacedResourceDelete[T, DeleteResult] {
  val asGenericResourceDelete: ResourceDelete[T, DeleteResult]

  /** Deletes an existing resource selected by its name
    * @param name
    *   Name of the resource
    * @param deleteOptions
    *   Delete options
    * @param namespace
    *   Namespace of the resource
    * @param dryRun
    *   If true, the request is sent to the server but it will not create the resource.
    * @param gracePeriod
    *   The duration in seconds before the object should be deleted. Value must be non-negative
    *   integer. The value zero indicates delete immediately. If this value is nil, the default
    *   grace period for the specified type will be used. Defaults to a per object value if not
    *   specified. zero means delete immediately.
    * @param propagationPolicy
    *   Whether and how garbage collection will be performed. Either this field or OrphanDependents
    *   may be set, but not both. The default policy is decided by the existing finalizer set in the
    *   metadata.finalizers and the resource-specific default policy. Acceptable values are:
    *   'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the
    *   dependents in the background; 'Foreground' - a cascading policy that deletes all dependents
    *   in the foreground.
    * @return
    *   Response from the Kubernetes API
    */
  def delete(
    name: String,
    deleteOptions: DeleteOptions,
    namespace: K8sNamespace,
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None
  ): IO[K8sFailure, DeleteResult] =
    asGenericResourceDelete.delete(
      name,
      deleteOptions,
      Some(namespace),
      dryRun,
      gracePeriod,
      propagationPolicy
    )

  /** Deletes an existing resource selected by its name and waits until it has gone
    * @param name
    *   Name of the resource
    * @param deleteOptions
    *   Delete options
    * @param namespace
    *   Namespace of the resource
    * @param dryRun
    *   If true, the request is sent to the server but it will not create the resource.
    * @param gracePeriod
    *   The duration in seconds before the object should be deleted. Value must be non-negative
    *   integer. The value zero indicates delete immediately. If this value is nil, the default
    *   grace period for the specified type will be used. Defaults to a per object value if not
    *   specified. zero means delete immediately.
    * @param propagationPolicy
    *   Whether and how garbage collection will be performed. Either this field or OrphanDependents
    *   may be set, but not both. The default policy is decided by the existing finalizer set in the
    *   metadata.finalizers and the resource-specific default policy. Acceptable values are:
    *   'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the
    *   dependents in the background; 'Foreground' - a cascading policy that deletes all dependents
    *   in the foreground.
    */
  def deleteAndWait(
    name: String,
    deleteOptions: DeleteOptions,
    namespace: K8sNamespace,
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None
  )(implicit ev: DeleteResult <:< Status): ZIO[Clock, K8sFailure, Unit] =
    asGenericResourceDelete.deleteAndWait(
      name,
      deleteOptions,
      Some(namespace),
      dryRun,
      gracePeriod,
      propagationPolicy
    )
}

trait ClusterResourceDelete[T, DeleteResult] {
  val asGenericResourceDelete: ResourceDelete[T, DeleteResult]

  /** Deletes an existing resource selected by its name
    * @param name
    *   Name of the resource
    * @param deleteOptions
    *   Delete options
    * @param dryRun
    *   If true, the request is sent to the server but it will not create the resource.
    * @param gracePeriod
    *   The duration in seconds before the object should be deleted. Value must be non-negative
    *   integer. The value zero indicates delete immediately. If this value is nil, the default
    *   grace period for the specified type will be used. Defaults to a per object value if not
    *   specified. zero means delete immediately.
    * @param propagationPolicy
    *   Whether and how garbage collection will be performed. Either this field or OrphanDependents
    *   may be set, but not both. The default policy is decided by the existing finalizer set in the
    *   metadata.finalizers and the resource-specific default policy. Acceptable values are:
    *   'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the
    *   dependents in the background; 'Foreground' - a cascading policy that deletes all dependents
    *   in the foreground.
    * @return
    *   Response from the Kubernetes API
    */
  def delete(
    name: String,
    deleteOptions: DeleteOptions,
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None
  ): IO[K8sFailure, DeleteResult] =
    asGenericResourceDelete.delete(
      name,
      deleteOptions,
      None,
      dryRun,
      gracePeriod,
      propagationPolicy
    )

  /** Deletes an existing resource selected by its name and waits until it has gone
    * @param name
    *   Name of the resource
    * @param deleteOptions
    *   Delete options
    * @param dryRun
    *   If true, the request is sent to the server but it will not create the resource.
    * @param gracePeriod
    *   The duration in seconds before the object should be deleted. Value must be non-negative
    *   integer. The value zero indicates delete immediately. If this value is nil, the default
    *   grace period for the specified type will be used. Defaults to a per object value if not
    *   specified. zero means delete immediately.
    * @param propagationPolicy
    *   Whether and how garbage collection will be performed. Either this field or OrphanDependents
    *   may be set, but not both. The default policy is decided by the existing finalizer set in the
    *   metadata.finalizers and the resource-specific default policy. Acceptable values are:
    *   'Orphan' - orphan the dependents; 'Background' - allow the garbage collector to delete the
    *   dependents in the background; 'Foreground' - a cascading policy that deletes all dependents
    *   in the foreground.
    */
  def deleteAndWait(
    name: String,
    deleteOptions: DeleteOptions,
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None
  )(implicit ev: DeleteResult <:< Status): ZIO[Clock, K8sFailure, Unit] =
    asGenericResourceDelete.deleteAndWait(
      name,
      deleteOptions,
      None,
      dryRun,
      gracePeriod,
      propagationPolicy
    )
}
