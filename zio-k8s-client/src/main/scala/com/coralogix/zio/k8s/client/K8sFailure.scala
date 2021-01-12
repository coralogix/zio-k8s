package com.coralogix.zio.k8s.client

import _root_.io.circe
import cats.data.NonEmptyList
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status
import sttp.model.StatusCode
import zio.ZIO

sealed trait K8sFailure
final case class Unauthorized(message: String) extends K8sFailure
final case class HttpFailure(message: String, code: StatusCode) extends K8sFailure
final case class DecodedFailure(status: Status, code: StatusCode) extends K8sFailure
final case class DeserializationFailure(error: NonEmptyList[circe.Error]) extends K8sFailure
object DeserializationFailure {
  def single(error: circe.Error): DeserializationFailure =
    DeserializationFailure(NonEmptyList.one(error))
}
final case class RequestFailure(reason: Throwable) extends K8sFailure
case object Gone extends K8sFailure
final case class InvalidEvent(eventType: String) extends K8sFailure
final case class UndefinedField(field: String) extends K8sFailure
case object NotFound extends K8sFailure

object K8sFailure {
  object syntax {
    implicit class K8sZIOSyntax[R, A](val f: ZIO[R, K8sFailure, A]) {
      def ifFound: ZIO[R, K8sFailure, Option[A]] =
        f.map(Some.apply)
          .catchSome {
            case NotFound => ZIO.none
          }
    }
  }
}
