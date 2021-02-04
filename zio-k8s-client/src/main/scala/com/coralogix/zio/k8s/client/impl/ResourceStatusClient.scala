package com.coralogix.zio.k8s.client.impl

import com.coralogix.zio.k8s.client.model.{K8sCluster, K8sNamespace, K8sObject, K8sResourceType}
import com.coralogix.zio.k8s.client.{K8sFailure, ResourceClientBase, ResourceStatus}
import io.circe._
import io.circe.syntax._
import sttp.client3.circe._
import sttp.client3.httpclient.zio.SttpClient
import zio.IO

final class ResourceStatusClient[StatusT: Encoder, T: K8sObject: Encoder: Decoder](
  override protected val resourceType: K8sResourceType,
  override protected val cluster: K8sCluster,
  override protected val backend: SttpClient.Service
) extends ResourceStatus[StatusT, T] with ResourceClientBase {
  import K8sObject._

  override def replaceStatus(
    of: T,
    updatedStatus: StatusT,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] =
    for {
      name     <- of.getName
      response <- handleFailures {
                    k8sRequest
                      .put(modifying(name = name, subresource = Some("status"), namespace, dryRun))
                      .body(toStatusUpdate(of, updatedStatus))
                      .response(asJson[T])
                      .send(backend)
                  }
    } yield response

  override def getStatus(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T] =
    handleFailures {
      k8sRequest
        .get(simple(Some(name), subresource = Some("status"), namespace))
        .response(asJson[T])
        .send(backend)
    }

  private def toStatusUpdate(of: T, newStatus: StatusT): Json =
    of.asJson.mapObject(
      _.remove("spec").add("status", newStatus.asJson)
    )
}
