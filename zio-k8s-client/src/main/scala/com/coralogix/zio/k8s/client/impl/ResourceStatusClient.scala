package com.coralogix.zio.k8s.client.impl

import com.coralogix.zio.k8s.client.config.backend.K8sBackend
import com.coralogix.zio.k8s.client.model.{ K8sCluster, K8sNamespace, K8sObject, K8sResourceType }
import com.coralogix.zio.k8s.client.{ K8sFailure, ResourceStatus }
import io.circe._
import io.circe.syntax._
import sttp.client3.circe._
import zio.IO

/** Generic implementation for [[ResourceStatus]]
  * @param resourceType
  *   Kubernetes resource metadata
  * @param cluster
  *   Configured Kubernetes cluster
  * @param backend
  *   Configured HTTP client
  * @tparam StatusT
  *   Status subresource type
  * @tparam T
  *   Resource type
  */
final class ResourceStatusClient[StatusT: Encoder, T: K8sObject: Encoder: Decoder](
  override protected val resourceType: K8sResourceType,
  override protected val cluster: K8sCluster,
  override protected val backend: K8sBackend
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
      response <- handleFailures("replaceStatus", namespace, None, None, None) {
                    k8sRequest.flatMap { request =>
                      request
                        .put(
                          modifying(name = name, subresource = Some("status"), namespace, dryRun)
                        )
                        .body(toStatusUpdate(of, updatedStatus))
                        .response(asJsonAccumulating[T])
                        .send(backend.value)
                    }
                  }
    } yield response

  override def getStatus(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T] =
    handleFailures("getStatus", namespace, name) {
      k8sRequest.flatMap { request =>
        request
          .get(simple(Some(name), subresource = Some("status"), namespace))
          .response(asJsonAccumulating[T])
          .send(backend.value)
      }
    }

  private def toStatusUpdate(of: T, newStatus: StatusT): Json =
    of.asJson.mapObject(
      _.remove("spec").add("status", newStatus.asJson)
    )
}
