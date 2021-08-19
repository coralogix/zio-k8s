package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.K8sNamespace
import zio.IO

/** Extra capability for [[Resource]] interfaces to manage status subresources
  * @tparam StatusT
  *   Status subresource type
  * @tparam T
  *   Resource type
  */
trait ResourceStatus[StatusT, T] {

  /** Replaces the status of a resource that was previously get from server.
    *
    * Use either [[getStatus]] or [[Resource.get]] to retrieve a value of the resource by name, and
    * then call this method to update its status.
    *
    * @param of
    *   The resource object to manipulate
    * @param updatedStatus
    *   Updated status value
    * @param namespace
    *   Namespace. For namespaced resources it must be Some, for cluster resources it must be None.
    * @param dryRun
    *   If true, the request is sent to the server but it will not create the resource.
    * @return
    *   Returns the updated resource (not just the status)
    */
  def replaceStatus(
    of: T,
    updatedStatus: StatusT,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false
  ): IO[K8sFailure, T]

  /** Get the status of a given subresource by name
    * @param name
    *   Name of the resource
    * @param namespace
    *   Namespace. For namespaced resources it must be Some, for cluster resources it must be None.
    * @return
    *   Returns the full resource object but with possibly the non-status fields absent.
    */
  def getStatus(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T]
}

/** Extra capability for [[NamespacedResource]] interfaces to manage status subresources
  * @tparam StatusT
  *   Status subresource type
  * @tparam T
  *   Resource type
  */
trait NamespacedResourceStatus[StatusT, T] {

  /** A more generic interface for the same resource
    */
  val asGenericResourceStatus: ResourceStatus[StatusT, T]

  /** Replaces the status of a resource that was previously get from server.
    *
    * Use either [[getStatus]] or [[NamespacedResource.get]] to retrieve a value of the resource by
    * name, and then call this method to update its status.
    *
    * @param of
    *   The resource object to manipulate
    * @param updatedStatus
    *   Updated status value
    * @param namespace
    *   Namespace of the resource
    * @param dryRun
    *   If true, the request is sent to the server but it will not create the resource.
    * @return
    *   Returns the updated resource (not just the status)
    */
  def replaceStatus(
    of: T,
    updatedStatus: StatusT,
    namespace: K8sNamespace,
    dryRun: Boolean = false
  ): IO[K8sFailure, T] =
    asGenericResourceStatus.replaceStatus(of, updatedStatus, Some(namespace), dryRun)

  /** Get the status of a given subresource by name
    * @param name
    *   Name of the resource
    * @param namespace
    *   Namespace of the resource
    * @return
    *   Returns the full resource object but with possibly the non-status fields absent.
    */
  def getStatus(name: String, namespace: K8sNamespace): IO[K8sFailure, T] =
    asGenericResourceStatus.getStatus(name, Some(namespace))
}

/** Extra capability for [[ClusterResource]] interfaces to manage status subresources
  * @tparam StatusT
  *   Status subresource type
  * @tparam T
  *   Resource type
  */
trait ClusterResourceStatus[StatusT, T] {

  /** A more generic interface for the same resource
    */
  val asGenericResourceStatus: ResourceStatus[StatusT, T]

  /** Replaces the status of a resource that was previously get from server.
    *
    * Use either [[getStatus]] or [[ClusterResource.get]] to retrieve a value of the resource by
    * name, and then call this method to update its status.
    *
    * @param of
    *   The resource object to manipulate
    * @param updatedStatus
    *   Updated status value
    * @param dryRun
    *   If true, the request is sent to the server but it will not create the resource.
    * @return
    *   Returns the updated resource (not just the status)
    */
  def replaceStatus(
    of: T,
    updatedStatus: StatusT,
    dryRun: Boolean = false
  ): IO[K8sFailure, T] =
    asGenericResourceStatus.replaceStatus(of, updatedStatus, None, dryRun)

  /** Get the status of a given subresource by name
    * @param name
    *   Name of the resource
    * @return
    *   Returns the full resource object but with possibly the non-status fields absent.
    */
  def getStatus(name: String): IO[K8sFailure, T] =
    asGenericResourceStatus.getStatus(name, None)
}
