package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.K8sNamespace
import zio.IO

trait ResourceStatus[StatusT, T] {
  def replaceStatus(
    of: T,
    updatedStatus: StatusT,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false
  ): IO[K8sFailure, T]

  def getStatus(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T]
}

trait NamespacedResourceStatus[StatusT, T] {
  val asGenericResourceStatus: ResourceStatus[StatusT, T]

  def replaceStatus(
    of: T,
    updatedResource: StatusT,
    namespace: K8sNamespace,
    dryRun: Boolean = false
  ): IO[K8sFailure, T]

  def getStatus(name: String, namespace: K8sNamespace): IO[K8sFailure, T]
}

trait ClusterResourceStatus[StatusT, T] {
  val asGenericResourceStatus: ResourceStatus[StatusT, T]

  def replaceStatus(
    of: T,
    updatedStatus: StatusT,
    dryRun: Boolean = false
  ): IO[K8sFailure, T]

  def getStatus(name: String): IO[K8sFailure, T]
}
