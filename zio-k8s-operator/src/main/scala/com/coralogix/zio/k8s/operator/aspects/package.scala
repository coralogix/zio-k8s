package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.model.{Added, Deleted, K8sObject, Modified, Reseted, TypedWatchEvent}
import com.coralogix.zio.k8s.operator.Operator.{Aspect, _}
import zio.Cause
import zio.logging.{Logging, log}

package object aspects {
  import K8sObject._

  /** Logs each watch event and event processor failures
    */
  def logEvents[T: K8sObject, E]: Aspect[Logging, E, T] =
    new Aspect[Logging, Nothing, T] {
      override def apply[R1 <: Logging, E1 >: Nothing](
        f: EventProcessor[R1, E1, T]
      ): EventProcessor[R1, E1, T] =
        (ctx: OperatorContext, event: TypedWatchEvent[T]) =>
          log.locally(OperatorLogging(ctx.withSpecificNamespace(event.namespace))) {
            (event match {
              case event @ Reseted            =>
                log.debug(s"State reseted") *>
                  f(ctx, event)
              case event @ Added(resource)    =>
                log.debug(s"Resource added: ${resource.metadata.flatMap(_.name).getOrElse("?")}") *>
                  f(ctx, event)
              case event @ Modified(resource) =>
                log.debug(
                  s"Resource modified: ${resource.metadata.flatMap(_.name).getOrElse("?")}"
                ) *>
                  f(ctx, event)
              case event @ Deleted(resource)  =>
                log.debug(
                  s"Resource deleted: ${resource.metadata.flatMap(_.name).getOrElse("?")}"
                ) *>
                  f(ctx, event)
            }).tapError { failure =>
              log.error(
                s"Failed to process ${event.getClass.getSimpleName} event",
                Cause.fail(failure)
              )
            }
          }
    }
}
