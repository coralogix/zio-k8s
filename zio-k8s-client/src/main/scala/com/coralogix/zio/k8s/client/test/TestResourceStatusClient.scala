package com.coralogix.zio.k8s.client.test

import com.coralogix.zio.k8s.client.model.K8sObject._
import com.coralogix.zio.k8s.client.model.K8sObjectStatus._
import com.coralogix.zio.k8s.client.model.{ K8sNamespace, K8sObject, K8sObjectStatus }
import com.coralogix.zio.k8s.client.{ K8sFailure, Resource, ResourceStatus }
import zio.IO

/** Test implementation of [[ResourceStatus]] to be used from unit tests
  * @param client
  *   The test client implementation to attach to
  * @tparam StatusT
  *   Status subresource type
  * @tparam T
  *   Resource type
  */
final class TestResourceStatusClient[StatusT, T](client: Resource[T])(implicit
  r: K8sObject[T],
  rs: K8sObjectStatus[T, StatusT]
) extends ResourceStatus[StatusT, T] {

  override def replaceStatus(
    of: T,
    updatedStatus: StatusT,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] =
    for {
      name   <- of.getName
      result <- client.replace(name, of.mapStatus(_ => updatedStatus), namespace, dryRun)
    } yield result

  override def getStatus(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T] =
    client.get(name, namespace)
}
