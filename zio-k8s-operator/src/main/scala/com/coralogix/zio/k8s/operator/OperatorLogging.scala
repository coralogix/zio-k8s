package com.coralogix.zio.k8s.operator

import zio.ZIO._
import zio.{ Cause, ZIO }

// TODO: clean this up if zio-logging is improved
object OperatorLogging {
  def apply[R, E, A](
    operatorContext: Operator.OperatorContext
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.logAnnotate("name", s"${operatorContext.resourceType.resourceType}Operator") {
      ZIO.logAnnotate("resource-type", operatorContext.resourceType.resourceType) {
        ZIO.logAnnotate("namespace", operatorContext.namespace.map(_.value).getOrElse("")) {
          effect
        }
      }

    }

  @FunctionalInterface
  trait ConvertableToThrowable[E] {
    def toThrowable(value: E): Throwable
  }
  object ConvertableToThrowable {
    implicit val throwable: ConvertableToThrowable[Throwable] = identity[Throwable]
    implicit val nothing: ConvertableToThrowable[Nothing] = _ => new RuntimeException("Nothing")
  }

  def logFailure[E: ConvertableToThrowable](
    message: String,
    cause: Cause[E]
  ): ZIO[Any, Nothing, Unit] = logError(message) *> logErrorCause(cause)
}
