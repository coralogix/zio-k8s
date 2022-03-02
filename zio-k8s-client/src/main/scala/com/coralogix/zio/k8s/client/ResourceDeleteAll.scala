package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.{
  FieldSelector,
  K8sNamespace,
  LabelSelector,
  PropagationPolicy
}
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, Status }
import zio.IO
import zio.Duration

/** Extra capability for [[Resource]] interfaces providing deleteAll
  *
  * It is separated because it is not supported by all resources.
  *
  * @tparam T
  *   Resource type
  */
trait ResourceDeleteAll[T] {

  /** Delete all resources matching the provided constraints
    *
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
    * @param fieldSelector
    *   Select the items to be deleted by field selectors. Not all fields are supported by the
    *   server.
    * @param labelSelector
    *   Select the items to be deleted by label selectors.
    * @return
    *   Status returned by the Kubernetes API
    */
  def deleteAll(
    deleteOptions: DeleteOptions,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): IO[K8sFailure, Status]
}

/** Extra capability for [[NamespacedResource]] interfaces providing deleteAll
  *
  * It is separated because it is not supported by all resources.
  *
  * @tparam T
  *   Resource type
  */
trait NamespacedResourceDeleteAll[T] {

  /** A more generic interface for the same resource
    */
  val asGenericResourceDeleteAll: ResourceDeleteAll[T]

  /** Delete all resources matching the provided constraints
    *
    * @param deleteOptions
    *   Delete options
    * @param namespace
    *   Namespace of the resources to be deleted
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
    * @param fieldSelector
    *   Select the items to be deleted by field selectors. Not all fields are supported by the
    *   server.
    * @param labelSelector
    *   Select the items to be deleted by label selectors.
    * @return
    *   Status returned by the Kubernetes API
    */
  def deleteAll(
    deleteOptions: DeleteOptions,
    namespace: K8sNamespace,
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): IO[K8sFailure, Status] =
    asGenericResourceDeleteAll.deleteAll(
      deleteOptions,
      Some(namespace),
      dryRun,
      gracePeriod,
      propagationPolicy,
      fieldSelector,
      labelSelector
    )
}

/** Extra capability for [[ClusterResource]] interfaces providing deleteAll
  *
  * It is separated because it is not supported by all resources.
  *
  * @tparam T
  *   Resource type
  */
trait ClusterResourceDeleteAll[T] {

  /** A more generic interface for the same resource
    */
  val asGenericResourceDeleteAll: ResourceDeleteAll[T]

  /** Delete all resources matching the provided constraints
    *
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
    * @param fieldSelector
    *   Select the items to be deleted by field selectors. Not all fields are supported by the
    *   server.
    * @param labelSelector
    *   Select the items to be deleted by label selectors.
    * @return
    *   Status returned by the Kubernetes API
    */
  def deleteAll(
    deleteOptions: DeleteOptions,
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): IO[K8sFailure, Status] =
    asGenericResourceDeleteAll.deleteAll(
      deleteOptions,
      None,
      dryRun,
      gracePeriod,
      propagationPolicy,
      fieldSelector,
      labelSelector
    )
}
