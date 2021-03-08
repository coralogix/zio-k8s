package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.K8sFailure
import zio.IO

/** Extension methods for Kubernetes resources with status subresource support
  *
  * This is a common implementation for all the implicit classes providing extension methods for
  * the generated Kuberentes model types. The extension methods are just delegating the calls to
  * the resource's [[K8sObjectStatus]] implementation.
  *
  * @tparam ResourceT Resource type to be extended
  * @tparam StatusT Status subresource type
  */
trait K8sObjectStatusOps[ResourceT, StatusT] {
  protected val obj: ResourceT
  protected val impl: K8sObjectStatus[ResourceT, StatusT]

  /** Gets the status of the object
    */
  def status: Optional[StatusT] = impl.status(obj)

  /** Gets the status of the object and fails with [[com.coralogix.zio.k8s.client.UndefinedField]] if it is not present.
    */
  def getStatus: IO[K8sFailure, StatusT] = impl.getStatus(obj)

  /** Returns an object with its status modified by the given function f
    * @param f Function to modify the status with
    * @return Object with modified status
    */
  def mapStatus(f: StatusT => StatusT): ResourceT = impl.mapStatus(f)(obj)
}
