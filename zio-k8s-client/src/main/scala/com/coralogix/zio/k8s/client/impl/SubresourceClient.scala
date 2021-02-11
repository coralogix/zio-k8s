package com.coralogix.zio.k8s.client.impl

import _root_.io.circe._
import com.coralogix.zio.k8s.client.model.{ K8sCluster, K8sNamespace, K8sResourceType }
import com.coralogix.zio.k8s.client.{ K8sFailure, ResourceClientBase, Subresource }
import sttp.client3.circe._
import sttp.client3.httpclient.zio._
import zio.IO

final class SubresourceClient[T: Encoder: Decoder](
  override protected val resourceType: K8sResourceType,
  override protected val cluster: K8sCluster,
  override protected val backend: SttpClient.Service,
  subresourceName: String
) extends Subresource[T] with ResourceClientBase {

  def get(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T] =
    handleFailures {
      k8sRequest
        .get(simple(Some(name), Some(subresourceName), namespace).addPath("status"))
        .response(asJson[T])
        .send(backend)
    }

  def replace(
    name: String,
    updatedValue: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] =
    handleFailures {
      k8sRequest
        .put(modifying(name, Some(subresourceName), namespace, dryRun))
        .body(updatedValue)
        .response(asJson[T])
        .send(backend)
    }

  def create(value: T, namespace: Option[K8sNamespace], dryRun: Boolean): IO[K8sFailure, T] =
    handleFailures {
      k8sRequest
        .post(creating(Some(subresourceName), namespace, dryRun))
        .body(value)
        .response(asJson[T])
        .send(backend)
    }

}
