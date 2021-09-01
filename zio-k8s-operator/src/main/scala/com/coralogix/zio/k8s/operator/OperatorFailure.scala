package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.internal.CircePrettyFailure
import com.coralogix.zio.k8s.client._
import com.coralogix.zio.k8s.operator.OperatorLogging.ConvertableToThrowable

/** Operator failure type
  * @tparam E
  *   Operator-specific error type
  */
sealed trait OperatorFailure[+E]

/** Operator failed with a Kubernetes error
  */
case class KubernetesFailure(failure: K8sFailure) extends OperatorFailure[Nothing]

/** Operator failed with an application-specific error
  * @tparam E
  *   Operator-specific error type
  */
case class OperatorError[E](error: E) extends OperatorFailure[E]

object OperatorFailure {
  implicit val k8sFailureToThrowable: ConvertableToThrowable[K8sFailure] = {
    case Unauthorized(reqInfo, message)         =>
      new RuntimeException(s"${reqInfoToString(reqInfo)} K8s authorization error: $message")
    case HttpFailure(reqInfo, message, code)    =>
      new RuntimeException(s"${reqInfoToString(reqInfo)} K8s HTTP error: $message with code $code")
    case DecodedFailure(reqInfo, status, code)  =>
      new RuntimeException(
        s"${reqInfoToString(reqInfo)} K8s error: ${status.message} with code $code"
      )
    case DeserializationFailure(reqInfo, error) =>
      new RuntimeException(
        s"${reqInfoToString(reqInfo)} K8s deserialization failure: ${error.toList.map(CircePrettyFailure.prettyPrint).mkString("\n")}"
      )
    case RequestFailure(reqInfo, reason)        =>
      new RuntimeException(s"${reqInfoToString(reqInfo)} K8s request error", reason)
    case Gone                                   =>
      new RuntimeException(s"Gone")
    case InvalidEvent(reqInfo, eventType)       =>
      new RuntimeException(s"${reqInfoToString(reqInfo)} Invalid event type: $eventType")
    case UndefinedField(fieldName)              =>
      new RuntimeException(s"Undefined field $fieldName")
    case NotFound                               =>
      new RuntimeException(s"Not found")
  }

  private def reqInfoToString(requestInfo: K8sRequestInfo): String =
    s"[${requestInfo.resourceType.group}/${requestInfo.resourceType.version}/${requestInfo.resourceType.resourceType} ${requestInfo.operation}]"

  implicit def toThrowable[E: ConvertableToThrowable]
    : ConvertableToThrowable[OperatorFailure[E]] = {
    case KubernetesFailure(failure) =>
      implicitly[ConvertableToThrowable[K8sFailure]].toThrowable(failure)
    case OperatorError(error)       =>
      implicitly[ConvertableToThrowable[E]].toThrowable(error)
  }
}
