package com.coralogix.zio.k8s.client.impl

import _root_.io.circe._
import cats.data.NonEmptyList
import com.coralogix.zio.k8s.client.config.backend.SttpStreamsAndWebSockets
import com.coralogix.zio.k8s.client.model.{K8sCluster, K8sNamespace, K8sResourceType}
import com.coralogix.zio.k8s.client.{K8sFailure, K8sRequestInfo, RequestFailure, Subresource}
import sttp.capabilities.zio.ZioStreams
import sttp.client3.circe._
import sttp.client3.{HttpError, ResponseException, asEither, asStreamAlwaysUnsafe, asStringAlways}
import zio.IO
import zio.stream.{ZPipeline, ZStream}

/** Generic implementation for [[Subresource]]
  * @param resourceType
  *   Kubernetes resource metadata
  * @param cluster
  *   Configured Kubernetes cluster
  * @param backend
  *   Configured HTTP client
  * @param subresourceName
  *   Name of the subresource
  * @tparam T
  *   Subresource type
  */
final class SubresourceClient[T: Encoder: Decoder](
  override protected val resourceType: K8sResourceType,
  override protected val cluster: K8sCluster,
  override protected val backend: SttpStreamsAndWebSockets,
  subresourceName: String
) extends Subresource[T] with ResourceClientBase {

  def get(
    name: String,
    namespace: Option[K8sNamespace],
    customParameters: Map[String, String] = Map.empty
  ): IO[K8sFailure, T] =
    handleFailures(s"get $subresourceName", namespace, name) {
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
    pipeline: ZPipeline[Any, K8sFailure, Byte, T],
    customParameters: Map[String, String] = Map.empty
  ): ZStream[Any, K8sFailure, T] =
    ZStream.unwrap {
      handleFailures(s"get $subresourceName", namespace, name) {
        k8sRequest
          .get(
            simple(Some(name), Some(subresourceName), namespace)
              .addParams(customParameters)
          )
          .response(
            asEither[ResponseException[
              String,
              NonEmptyList[Error]
            ], ZioStreams.BinaryStream, ZioStreams](
              asStringAlways.mapWithMetadata { case (body, meta) =>
                HttpError(body, meta.code)
                  .asInstanceOf[ResponseException[String, NonEmptyList[Error]]]
              },
              asStreamAlwaysUnsafe(ZioStreams)
            )
          )
          .send(backend)
      }.map { (stream: ZioStreams.BinaryStream) =>
        stream
          .mapError(
            RequestFailure(K8sRequestInfo(resourceType, s"get $subresourceName", namespace), _)
          )
          .via(pipeline)
      }
    }

  def replace(
    name: String,
    updatedValue: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] =
    handleFailures(s"replace $subresourceName", namespace, name) {
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
    handleFailures(s"create $subresourceName", namespace, name) {
      k8sRequest
        .post(modifying(name, Some(subresourceName), namespace, dryRun))
        .body(value)
        .response(asJsonAccumulating[T])
        .send(backend)
    }
}
