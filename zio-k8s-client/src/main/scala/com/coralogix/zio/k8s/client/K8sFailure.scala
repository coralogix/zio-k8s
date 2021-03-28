package com.coralogix.zio.k8s.client

import _root_.io.circe
import cats.data.NonEmptyList
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status
import sttp.model.StatusCode
import zio.ZIO

/** Error type of the Kubernetes client
  */
sealed trait K8sFailure

/** Request unauthorized
  *
  * Indicates that the Kubernetes API returned a HTTP 401 response.
  * @param message Message of the response
  */
final case class Unauthorized(requestInfo: K8sRequestInfo, message: String) extends K8sFailure

/** Failed HTTP response
  *
  * Indicates that the response from the Kubernetes API has a non-successful
  * status code and it's body did not contain a [[com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status]] value.
  *
  * In case the error is a standard Kubernetes error message, the error type
  * will be [[DecodedFailure]].
  *
  * Note that some specific failure types are encoded by their own failure type.
  * See [[Unauthorized]], [[Gone]] and [[NotFound]].
  *
  * @param message Response message
  * @param code Response status code
  */
final case class HttpFailure(requestInfo: K8sRequestInfo, message: String, code: StatusCode)
    extends K8sFailure

/** Failed Kubernetes API request
  *
  * Note that some specific failure types are encoded by their own failure type.
  * See [[Unauthorized]], [[Gone]] and [[NotFound]].
  *
  * @param status The Kubernetes [[com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status]] value returned in the failure response
  * @param code Response status code
  */
final case class DecodedFailure(requestInfo: K8sRequestInfo, status: Status, code: StatusCode)
    extends K8sFailure

/** Error indicating that Kubernetes API responded with success, but the response body
  * could not be deserialized to the expected data type.
  * @param error The list of deserialization errors
  */
final case class DeserializationFailure(
  requestInfo: K8sRequestInfo,
  error: NonEmptyList[circe.Error]
) extends K8sFailure
object DeserializationFailure {
  def single(requestInfo: K8sRequestInfo, error: circe.Error): DeserializationFailure =
    DeserializationFailure(requestInfo, NonEmptyList.one(error))
}

/** Failed to send the HTTP request to the Kubernetes API
  * @param reason The failure reason
  */
final case class RequestFailure(requestInfo: K8sRequestInfo, reason: Throwable) extends K8sFailure

/** The server returned with HTTP 410 (Gone) which has a specific role in
  * handling watch streams.
  */
case object Gone extends K8sFailure

/** An unsupported event type was sent in a watch stream
  * @param eventType The unrecognized event type from the server
  */
final case class InvalidEvent(requestInfo: K8sRequestInfo, eventType: String) extends K8sFailure

/** Error produced by the generated getter methods on Kubernetes data structures.
  *
  * Indicates that the requested field is not present.
  *
  * @param field Field name
  */
final case class UndefinedField(field: String) extends K8sFailure

/** The sever returned with HTTP 404 (NotFound).
  *
  * See the [[K8sFailure.syntax.K8sZIOSyntax.ifFound]] extension method for a convenient way to
  * handle these errors.
  */
case object NotFound extends K8sFailure

object K8sFailure {

  /** Package containing extension methods for ZIO effects failing with [[K8sFailure]]
    */
  object syntax {
    implicit class K8sZIOSyntax[R, A](val f: ZIO[R, K8sFailure, A]) {

      /** Captures the [[NotFound]] error and converts the result value to an Option
        */
      def ifFound: ZIO[R, K8sFailure, Option[A]] =
        f.map(Some.apply)
          .catchSome { case NotFound =>
            ZIO.none
          }
    }
  }
}
