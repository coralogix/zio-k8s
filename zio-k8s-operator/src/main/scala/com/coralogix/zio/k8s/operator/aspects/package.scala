package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.model.{
  Added,
  Deleted,
  K8sObject,
  Modified,
  Reseted,
  TypedWatchEvent
}
import com.coralogix.zio.k8s.operator.Operator.{ Aspect, _ }
import com.coralogix.zio.k8s.operator.OperatorLogging._
import zio.{ Cause, ZIO }

package object aspects {
  import K8sObject._

  /** Logs each watch event and event processor failures
    */
  def logEvents[T: K8sObject, E]: Aspect[Any, E, T] =
    new Aspect[Any, Nothing, T] {
      override def apply[R1, E1 >: Nothing](
        f: EventProcessor[R1, E1, T]
      ): EventProcessor[R1, E1, T] =
        (ctx: OperatorContext, event: TypedWatchEvent[T]) =>
          OperatorLogging(ctx.withSpecificNamespace(event.namespace)) {
            (event match {
              case event @ Reseted()          =>
                ZIO.logDebug(s"State reseted") *>
                  f(ctx, event)
              case event @ Added(resource)    =>
                ZIO.logDebug(
                  s"Resource added: ${resource.metadata.flatMap(_.name).getOrElse("?")}"
                ) *>
                  f(ctx, event)
              case event @ Modified(resource) =>
                ZIO.logDebug(
                  s"Resource modified: ${resource.metadata.flatMap(_.name).getOrElse("?")}"
                ) *>
                  f(ctx, event)
              case event @ Deleted(resource)  =>
                ZIO.logDebug(
                  s"Resource deleted: ${resource.metadata.flatMap(_.name).getOrElse("?")}"
                ) *>
                  f(ctx, event)
            }).tapError { _ =>
              ZIO.logError(
                s"Failed to process ${event.getClass.getSimpleName} event"
              )
            }
          }
    }
}
