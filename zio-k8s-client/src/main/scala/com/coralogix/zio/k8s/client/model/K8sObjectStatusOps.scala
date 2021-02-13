package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.K8sFailure
import zio.IO

trait K8sObjectStatusOps[ResourceT, StatusT] {
  protected val obj: ResourceT
  protected val impl: K8sObjectStatus[ResourceT, StatusT]

  def status: Optional[StatusT] = impl.status(obj)
  def getStatus: IO[K8sFailure, StatusT] = impl.getStatus(obj)
  def mapStatus(f: StatusT => StatusT): ResourceT = impl.mapStatus(f)(obj)
}
