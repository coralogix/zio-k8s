package com.coralogix.zio.k8s.client.impl

import cats.data.NonEmptyList
import com.coralogix.zio.k8s.client._
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status
import io.circe.parser.{ decode, decodeAccumulating }
import io.circe.{ Decoder, Error }
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.json.RichResponseAs
import sttp.client3.{
  asString,
  basicRequest,
  DeserializationException,
  Empty,
  HttpError,
  IsOption,
  RequestT,
  Response,
  ResponseAs,
  ResponseException,
  ShowError,
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

  protected def connecting(
    name: String,
    subresource: String,
    namespace: Option[K8sNamespace]
  ): Uri = K8sConnectUri(
    resourceType,
    name,
    subresource,
    namespace
  ).toUri(cluster)

  protected def handleFailures[A](operation: String)(
    f: Task[Response[Either[ResponseException[String, NonEmptyList[Error]], A]]]
  ): IO[K8sFailure, A] = {
    val reqInfo = K8sRequestInfo(resourceType, operation)
    f.mapError(RequestFailure.apply(reqInfo, _))
      .flatMap { response =>
        response.body match {
          case Left(HttpError(error, StatusCode.Unauthorized)) =>
            IO.fail(Unauthorized(reqInfo, error))
          case Left(HttpError(_, StatusCode.Gone))             =>
            IO.fail(Gone)
          case Left(HttpError(_, StatusCode.NotFound))         =>
            IO.fail(NotFound)
          case Left(HttpError(error, code))                    =>
            decode[Status](error) match {
              case Left(_)       =>
                IO.fail(HttpFailure(reqInfo, error, code))
              case Right(status) =>
                IO.fail(DecodedFailure(reqInfo, status, code))
            }
          case Left(DeserializationException(_, errors))       =>
            IO.fail(DeserializationFailure(reqInfo, errors))
          case Right(value)                                    =>
            IO.succeed(value)
        }
      }
  }

  /** If the response is successful (2xx), tries to deserialize the body from a string into JSON.
    * Returns:
    *   - `Right(b)` if the parsing was successful
    *   - `Left(HttpError(String))` if the response code was other than 2xx (deserialization is not
    *     attempted)
    *   - `Left(DeserializationException)` if there's an error during deserialization
    */
  protected def asJsonAccumulating[B: Decoder: IsOption]
    : ResponseAs[Either[ResponseException[String, NonEmptyList[io.circe.Error]], B], Any] = {
    import ResourceClientBase.showCirceErrors
    asString.mapWithMetadata(ResponseAs.deserializeRightWithError(deserializeJson)).showAsJson
  }

  private def deserializeJson[B: Decoder: IsOption]
    : String => Either[NonEmptyList[io.circe.Error], B] =
    sanitize[B].andThen(decodeAccumulating[B]).andThen(_.toEither)

  private def sanitize[T: IsOption]: String => String = { s =>
    if (implicitly[IsOption[T]].isOption && s.trim.isEmpty) "null" else s
  }
}

object ResourceClientBase {
  implicit val showCirceErrors: ShowError[NonEmptyList[io.circe.Error]] =
    (t: NonEmptyList[Error]) => t.map(_.getMessage).toList.mkString("\n")
}
