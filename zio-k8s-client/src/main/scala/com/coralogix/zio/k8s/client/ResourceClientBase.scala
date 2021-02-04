package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.{K8sCluster, K8sCreatorUri, K8sModifierUri, K8sNamespace, K8sPaginatedUri, K8sResourceType, K8sSimpleUri, K8sWatchUri}
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status
import io.circe.Error
import io.circe.parser.decode
import sttp.client3.{DeserializationException, Empty, HttpError, RequestT, Response, ResponseException, basicRequest}
import sttp.client3.httpclient.zio.SttpClient
import sttp.model.{StatusCode, Uri}
import zio.{IO, Task}

trait ResourceClientBase {
  protected val resourceType: K8sResourceType
  protected val cluster: K8sCluster
  protected val backend: SttpClient.Service

  protected val k8sRequest: RequestT[Empty, Either[String, String], Any] =
    basicRequest.auth.bearer(cluster.token)

  protected def simple(
                        name: Option[String],
                        subresource: Option[String],
                        namespace: Option[K8sNamespace]
                      ): Uri =
    K8sSimpleUri(resourceType, name, subresource, namespace).toUri(cluster)

  protected def creating(
                          subresource: Option[String],
                          namespace: Option[K8sNamespace],
                          dryRun: Boolean
                        ): Uri =
    K8sCreatorUri(resourceType, subresource, namespace, dryRun).toUri(cluster)

  protected def modifying(
                           name: String,
                           subresource: Option[String],
                           namespace: Option[K8sNamespace],
                           dryRun: Boolean
                         ): Uri =
    K8sModifierUri(resourceType, name, subresource, namespace, dryRun).toUri(cluster)

  protected def paginated(
                           namespace: Option[K8sNamespace],
                           limit: Int,
                           continueToken: Option[String]
                         ): Uri =
    K8sPaginatedUri(resourceType, namespace, limit, continueToken).toUri(cluster)

  protected def watching(namespace: Option[K8sNamespace], resourceVersion: Option[String]): Uri =
    K8sWatchUri(resourceType, namespace, resourceVersion).toUri(cluster)

  protected def handleFailures[A](
                                   f: Task[Response[Either[ResponseException[String, Error], A]]]
                                 ): IO[K8sFailure, A] =
    f.mapError(RequestFailure.apply)
      .flatMap { response =>
        response.body match {
          case Left(HttpError(error, StatusCode.Unauthorized)) =>
            IO.fail(Unauthorized(error))
          case Left(HttpError(error, StatusCode.Gone))         =>
            IO.fail(Gone)
          case Left(HttpError(error, StatusCode.NotFound))     =>
            IO.fail(NotFound)
          case Left(HttpError(error, code))                    =>
            decode[Status](error) match {
              case Left(_)       =>
                IO.fail(HttpFailure(error, code))
              case Right(status) =>
                IO.fail(DecodedFailure(status, code))
            }
          case Left(DeserializationException(_, error))        =>
            IO.fail(DeserializationFailure.single(error))
          case Right(value)                                    =>
            IO.succeed(value)
        }
      }
}