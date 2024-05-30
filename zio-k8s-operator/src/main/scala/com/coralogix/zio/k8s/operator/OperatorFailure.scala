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
    case Unauthorized(reqInfo, message)            =>
      new RuntimeException(formatErrorWithK8sContext(reqInfo, s"K8s authorization error: $message"))
    case HttpFailure(reqInfo, message, code)       =>
      new RuntimeException(
        formatErrorWithK8sContext(reqInfo, s"K8s HTTP error: $message with code $code")
      )
    case CodingFailure(reqInfo, failure)           =>
      new RuntimeException(
        formatErrorWithK8sContext(reqInfo, "K8s character coding error"),
        failure
      )
    case DecodedFailure(reqInfo, status, code)     =>
      new RuntimeException(
        formatErrorWithK8sContext(reqInfo, s"K8s error: ${status.message} with code $code")
      )
    case DeserializationFailure(reqInfo, error)    =>
      val prettyPrintedError = error.toList.map(CircePrettyFailure.prettyPrint).mkString("\n")
      new RuntimeException(
        formatErrorWithK8sContext(reqInfo, s"K8s deserialization failure: $prettyPrintedError")
      )
    case RequestFailure(reqInfo, reason)           =>
      new RuntimeException(formatErrorWithK8sContext(reqInfo, "K8s request error"), reason)
    case Gone                                      =>
      new RuntimeException(s"Gone")
    case InvalidEvent(reqInfo, eventType)          =>
      new RuntimeException(formatErrorWithK8sContext(reqInfo, s"Invalid event type: $eventType"))
    case UndefinedField(fieldName)                 =>
      new RuntimeException(s"Undefined field $fieldName")
    case ErrorEvent(status, message, reason, code) =>
      new RuntimeException(
        s"Received error event. Status $status, message: $message, reason: $reason, code: $code"
      )
    case NotFound                                  =>
      new RuntimeException(s"Not found")
  }

  private def formatErrorWithK8sContext(requestInfo: K8sRequestInfo, message: String): String = {
    val namespace = requestInfo.namespace.fold("")(n => s"namespace=$n")
    val name = requestInfo.name.fold("")(n => s"name=$n")
    val fieldSelector = requestInfo.fieldSelector.fold("")(n => s"field_selector=$n")
    val labelSelector = requestInfo.labelSelector.fold("")(n => s"label_selector=$n")
    s"[${requestInfo.resourceType.group}/${requestInfo.resourceType.version}/${requestInfo.resourceType.resourceType} ${requestInfo.operation}] $message ($namespace $name $fieldSelector $labelSelector)"
  }

  implicit def toThrowable[E: ConvertableToThrowable]
    : ConvertableToThrowable[OperatorFailure[E]] = {
    case KubernetesFailure(failure) =>
      implicitly[ConvertableToThrowable[K8sFailure]].toThrowable(failure)
    case OperatorError(error)       =>
      implicitly[ConvertableToThrowable[E]].toThrowable(error)
  }
}
