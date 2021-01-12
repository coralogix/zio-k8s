package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.model.K8sNamespace
import zio.Cause._
import zio.{ Cause, ZIO }
import zio.logging.{ log, LogAnnotation, LogContext, Logging }

// TODO: clean this up if zio-logging is improved
object OperatorLogging {
  val logResourceType: LogAnnotation[Option[String]] =
    LogAnnotation[Option[String]]("resource-type", None, (_, t) => t, _.getOrElse(""))
  val logNamespace: LogAnnotation[Option[K8sNamespace]] = LogAnnotation[Option[K8sNamespace]](
    "namespace",
    None,
    (_, t) => t,
    _.map(_.value).getOrElse("")
  )

  def apply(operatorContext: Operator.OperatorContext)(logContext: LogContext): LogContext =
    logContext
      .annotate(LogAnnotation.Name, s"${operatorContext.resourceType.resourceType}Operator" :: Nil)
      .annotate(logResourceType, Some(operatorContext.resourceType.resourceType))
      .annotate(logNamespace, operatorContext.namespace)

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
  ): ZIO[Logging, Nothing, Unit] =
    cause match {
      case Empty() =>
        log.error(message)
      case Fail(value) =>
        log.throwable(message, implicitly[ConvertableToThrowable[E]].toThrowable(value))
      case Die(value) =>
        log.throwable(message, value)
      case Interrupt(fiberId) =>
        log.throwable(message, new InterruptedException(fiberId.toString))
      case Traced(cause, trace) =>
        logFailure(message, cause)
      case Then(left, right) =>
        logFailure(message + s" #1 ++", left) *>
          logFailure(message + s" ++ #2", right)
      case Both(left, right) =>
        logFailure(message + s" #1 &&", left) *>
          logFailure(message + s" && #2", right)
      case _ =>
        log.error(message, cause)
    }
}
