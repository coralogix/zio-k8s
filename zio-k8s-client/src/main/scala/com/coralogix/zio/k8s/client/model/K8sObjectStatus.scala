package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.{ K8sFailure, UndefinedField }
import zio.{ IO, ZIO }

trait K8sObjectStatus[ResourceT, StatusT] {
  def status(obj: ResourceT): Optional[StatusT]
  def mapStatus(f: StatusT => StatusT)(obj: ResourceT): ResourceT

  def getStatus(obj: ResourceT): IO[K8sFailure, StatusT] =
    ZIO.fromEither(status(obj).toRight(UndefinedField("status")))
}

object K8sObjectStatus {
  implicit class StatusOps[ResourceT, StatusT](protected val obj: ResourceT)(implicit
    protected val impl: K8sObjectStatus[ResourceT, StatusT]
  ) extends K8sObjectStatusOps[ResourceT, StatusT]
}
