package com.coralogix.zio.k8s.client.impl

import com.coralogix.zio.k8s.client.model.{ K8sCluster, K8sNamespace, K8sObject, K8sResourceType }
import com.coralogix.zio.k8s.client.{ K8sFailure, ResourceStatus }
import io.circe._
import io.circe.syntax._
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import sttp.client3.circe._
import zio.{ IO, Task }

/** Generic implementation for [[ResourceStatus]]
  * @param resourceType Kubernetes resource metadata
  * @param cluster Configured Kubernetes cluster
  * @param backend Configured HTTP client
  * @tparam StatusT Status subresource type
  * @tparam T Resource type
  */
final class ResourceStatusClient[StatusT: Encoder, T: K8sObject: Encoder: Decoder](
  override protected val resourceType: K8sResourceType,
  override protected val cluster: K8sCluster,
  override protected val backend: SttpBackend[Task, ZioStreams with WebSockets]
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
