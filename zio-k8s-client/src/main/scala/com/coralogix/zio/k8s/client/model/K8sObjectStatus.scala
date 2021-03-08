package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.{ K8sFailure, UndefinedField }
import zio.{ IO, ZIO }

/** Common operations for every Kubernetes resource type supporting
  * status subresources.
  *
  * @tparam ResourceT Resource type
  * @tparam StatusT Subresource type
  */
trait K8sObjectStatus[ResourceT, StatusT] {

  /** Gets the status of the object
    */
  def status(obj: ResourceT): Optional[StatusT]

  /** Modifies the status of the object with the given function f
    * @param f Function modifying the status
    */
  def mapStatus(f: StatusT => StatusT)(obj: ResourceT): ResourceT

  /** Get the status of the object and fail with [[UndefinedField]] if it is not present.
    */
  def getStatus(obj: ResourceT): IO[K8sFailure, StatusT] =
    ZIO.fromEither(status(obj).toRight(UndefinedField("status")))
}

object K8sObjectStatus {

  /** Extension methods for resource types implementing the [[K8sObjectStatus]] type class
    */
  implicit class StatusOps[ResourceT, StatusT](protected val obj: ResourceT)(implicit
    protected val impl: K8sObjectStatus[ResourceT, StatusT]
  ) extends K8sObjectStatusOps[ResourceT, StatusT]
}
