package com.coralogix.operator.logic

import zio.k8s.client.internal.CircePrettyFailure
import zio.k8s.client.UndefinedField
import com.coralogix.operator.logging.ConvertableToThrowable
import zio.k8s.client.{
  DecodedFailure,
  DeserializationFailure,
  Gone,
  HttpFailure,
  InvalidEvent,
  K8sFailure,
  NotFound,
  RequestFailure,
  Unauthorized,
  UndefinedField
}

sealed trait OperatorFailure
case class KubernetesFailure(failure: K8sFailure) extends OperatorFailure
case class GrpcFailure(status: io.grpc.Status) extends OperatorFailure
case class UndefinedGrpcField(name: String) extends OperatorFailure

object OperatorFailure {
  implicit val toThrowable: ConvertableToThrowable[OperatorFailure] = {
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
    case GrpcFailure(status) =>
      status.asException()
    case UndefinedGrpcField(fieldName) =>
      new RuntimeException(s"Undefined field in gRPC data: $fieldName")
  }
}
