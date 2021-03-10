package com.coralogix.zio.k8s.client.impl

import _root_.io.circe._
import cats.data.NonEmptyList
import com.coralogix.zio.k8s.client.model.{ K8sCluster, K8sNamespace, K8sResourceType }
import com.coralogix.zio.k8s.client.{ K8sFailure, RequestFailure, Subresource }
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.circe._
import sttp.client3.{
  asEither,
  asStreamAlwaysUnsafe,
  asStringAlways,
  HttpError,
  ResponseException,
  SttpBackend
}
import zio.stream.{ ZStream, ZTransducer }
import zio.{ IO, Task }

/** Generic implementation for [[Subresource]]
  * @param resourceType Kubernetes resource metadata
  * @param cluster Configured Kubernetes cluster
  * @param backend Configured HTTP client
  * @param subresourceName Name of the subresource
  * @tparam T Subresource type
  */
final class SubresourceClient[T: Encoder: Decoder](
  override protected val resourceType: K8sResourceType,
  override protected val cluster: K8sCluster,
  override protected val backend: SttpBackend[Task, ZioStreams with WebSockets],
  subresourceName: String
) extends Subresource[T] with ResourceClientBase {

  def get(
    name: String,
    namespace: Option[K8sNamespace],
    customParameters: Map[String, String] = Map.empty
  ): IO[K8sFailure, T] =
    handleFailures {
      k8sRequest
        .get(
          simple(Some(name), Some(subresourceName), namespace)
            .addParams(customParameters)
        )
        .response(asJsonAccumulating[T])
        .send(backend)
    }

  def streamingGet(
    name: String,
    namespace: Option[K8sNamespace],
    transducer: ZTransducer[Any, K8sFailure, Byte, T],
    customParameters: Map[String, String] = Map.empty
  ): ZStream[Any, K8sFailure, T] =
    ZStream.unwrap {
      handleFailures {
        k8sRequest
          .get(
            simple(Some(name), Some(subresourceName), namespace)
              .addParams(customParameters)
          )
          .response(
            asEither(
              asStringAlways.mapWithMetadata { case (body, meta) =>
                HttpError(body, meta.code)
                  .asInstanceOf[ResponseException[String, NonEmptyList[Error]]]
              },
              asStreamAlwaysUnsafe(ZioStreams)
            )
          )
          .send(backend)
      }.map { stream =>
        stream
          .mapError(RequestFailure)
          .transduce(transducer)
      }
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
        .response(asJsonAccumulating[T])
        .send(backend)
    }

  def create(
    name: String,
    value: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] =
    handleFailures {
      k8sRequest
        .post(modifying(name, Some(subresourceName), namespace, dryRun))
        .body(value)
        .response(asJsonAccumulating[T])
        .send(backend)
    }
}
