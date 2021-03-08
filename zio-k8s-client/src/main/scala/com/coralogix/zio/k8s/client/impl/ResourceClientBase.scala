package com.coralogix.zio.k8s.client.impl

import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.client._
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status
import io.circe.Error
import io.circe.parser.decode
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.{
  basicRequest,
  DeserializationException,
  Empty,
  HttpError,
  RequestT,
  Response,
  ResponseException,
  SttpBackend
}
import sttp.model.{ StatusCode, Uri }
import zio.duration.Duration
import zio.{ IO, Task }

trait ResourceClientBase {
  protected val resourceType: K8sResourceType
  protected val cluster: K8sCluster
  protected val backend: SttpBackend[Task, ZioStreams with WebSockets]

  protected val k8sRequest: RequestT[Empty, Either[String, String], Any] =
    cluster.applyToken match {
      case Some(f) => f(basicRequest)
      case None    => basicRequest
    }

  protected def simple(
    name: Option[String],
    subresource: Option[String],
    namespace: Option[K8sNamespace]
  ): Uri =
    K8sSimpleUri(resourceType, name, subresource, namespace).toUri(cluster)

  protected def creating(
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): Uri =
    K8sCreatorUri(resourceType, namespace, dryRun).toUri(cluster)

  protected def modifying(
    name: String,
    subresource: Option[String],
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): Uri =
    K8sModifierUri(resourceType, name, subresource, namespace, dryRun).toUri(cluster)

  protected def deleting(
    name: String,
    subresource: Option[String],
    namespace: Option[K8sNamespace],
    dryRun: Boolean,
    gracePeriod: Option[Duration],
    propagationPolicy: Option[PropagationPolicy]
  ): Uri =
    K8sDeletingUri(
      resourceType,
      name,
      subresource,
      namespace,
      dryRun,
      gracePeriod,
      propagationPolicy
    ).toUri(cluster)

  protected def deletingMany(
    namespace: Option[K8sNamespace],
    dryRun: Boolean,
    gracePeriod: Option[Duration],
    propagationPolicy: Option[PropagationPolicy],
    fieldSelector: Option[FieldSelector],
    labelSelector: Option[LabelSelector]
  ): Uri =
    K8sDeletingManyUri(
      resourceType,
      namespace,
      dryRun,
      gracePeriod,
      propagationPolicy,
      fieldSelector,
      labelSelector
    ).toUri(cluster)

  protected def paginated(
    namespace: Option[K8sNamespace],
    limit: Int,
    continueToken: Option[String],
    fieldSelector: Option[FieldSelector],
    labelSelector: Option[LabelSelector],
    resourceVersion: ListResourceVersion
  ): Uri =
    K8sPaginatedUri(
      resourceType,
      namespace,
      limit,
      continueToken,
      fieldSelector,
      labelSelector,
      resourceVersion
    ).toUri(cluster)

  protected def watching(
    namespace: Option[K8sNamespace],
    resourceVersion: Option[String],
    fieldSelector: Option[FieldSelector],
    labelSelector: Option[LabelSelector]
  ): Uri =
    K8sWatchUri(
      resourceType,
      namespace,
      resourceVersion,
      allowBookmarks = true,
      fieldSelector,
      labelSelector
    ).toUri(
      cluster
    )

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
