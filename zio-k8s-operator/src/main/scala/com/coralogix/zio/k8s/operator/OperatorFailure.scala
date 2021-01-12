package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.internal.CircePrettyFailure
import com.coralogix.zio.k8s.client._
import com.coralogix.zio.k8s.operator.OperatorLogging.ConvertableToThrowable

sealed trait OperatorFailure[+E]
case class KubernetesFailure(failure: K8sFailure) extends OperatorFailure[Nothing]
case class OperatorError[E](error: E) extends OperatorFailure[E]

object OperatorFailure {
  implicit def toThrowable[E: ConvertableToThrowable]
    : ConvertableToThrowable[OperatorFailure[E]] = {
    case KubernetesFailure(failure) =>
      failure match {
        case Unauthorized(message) =>
          new RuntimeException(s"K8s authorization error: $message")
        case HttpFailure(message, code) =>
          new RuntimeException(s"K8s HTTP error: $message with code $code")
        case DecodedFailure(status, code) =>
          new RuntimeException(s"K8s error: ${status.message} with code $code")
        case DeserializationFailure(error) =>
          new RuntimeException(
            s"K8s deserialization failure: ${error.toList.map(CircePrettyFailure.prettyPrint).mkString("\n")}"
          )
        case RequestFailure(reason) =>
          new RuntimeException(s"K8s request error", reason)
        case Gone =>
          new RuntimeException(s"Gone")
        case InvalidEvent(eventType) =>
          new RuntimeException(s"Invalid event type: $eventType")
        case UndefinedField(fieldName) =>
          new RuntimeException(s"Undefined field $fieldName")
        case NotFound =>
          new RuntimeException(s"Not found")
      }
    case OperatorError(error) =>
      implicitly[ConvertableToThrowable[E]].toThrowable(error)
  }
}
