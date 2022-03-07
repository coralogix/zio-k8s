---
id: operator_operator
title:  "Implementing operators"
---

## Event processor

The library defines an `Operator` by providing an `EventProcessor`:

```scala
trait EventProcessor[-R, +E, T] {
  def apply(context: OperatorContext, event: TypedWatchEvent[T]): ZIO[R, OperatorFailure[E], Unit]
}
```

To start an operator, use either the `Operator.cluster` or `Operator.namespaced` functions:

```scala mdoc:silent
import com.coralogix.zio.k8s.client.NamespacedResource
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.model.core.v1.Pod
import com.coralogix.zio.k8s.operator._
import com.coralogix.zio.k8s.operator.Operator._

import zio._
import zio.Clock

sealed trait CustomOperatorFailures

val eventProcessor: EventProcessor[Clock, CustomOperatorFailures, Pod] = 
    (ctx, event) => 
        event match {
            case Reseted() =>
                ZIO.unit
            case Added(item) =>
                ZIO.unit
            case Modified(item) =>
                ZIO.unit
            case Deleted(item) =>
                ZIO.unit
        }

val operator = 
    Operator.namespaced(
        eventProcessor
    )(namespace = None, buffer = 1024)
```

Passing `None` as namespace to `Operator.namespaced` means watching _all_ namespaces.

## Aspects
The event processor logic can be modified by various _aspects_ such as logging and monitoring. The library currently only provides a single _logging aspect_ that can be applied with the `@@` operator:

```scala mdoc
import com.coralogix.zio.k8s.operator.aspects._

val operator2 = 
    Operator.namespaced(
        eventProcessor @@ logEvents
    )(namespace = None, buffer = 1024)
```

### Defining an aspect
In this example we define another aspect for monitoring the _event processing time_ and the number of different events processed with _Prometheus_ metrics using the [zio-metrics](https://zio.github.io/zio-metrics/) library.

Assuming we have a type `OperatorMetrics` with the following fields:

```scala mdoc
import zio.metrics.prometheus.helpers._
import zio.metrics.prometheus.{ Counter, Histogram, Registry }

case class OperatorMetrics(
  eventCounter: Counter,
  eventProcessingTime: Histogram
)

object OperatorMetrics {
    def labels(
        event: TypedWatchEvent[_],
        resourceName: String,
        namespace: Option[K8sNamespace]
    ): Array[String] = {
        val eventType = event match {
            case Reseted() => "reseted"
            case Added(_) => "added"
            case Modified(_) => "modified"
            case Deleted(_) => "deleted"
        }
        Array(eventType, resourceName, namespace.map(_.value).getOrElse(""))
  }
}
```

we can define a `metered` aspect:

```scala mdoc
import zio.Clock

def metered[T, E](operatorMetrics: OperatorMetrics): Aspect[Clock, E, T] =
    new Aspect[Clock, E, T] {
      override def apply[R1 <: Clock, E1 >: E](
        f: EventProcessor[R1, E1, T]
      ): EventProcessor[R1, E1, T] =
        (ctx, event) => {
          // Using the operator context and the event to produce some Prometheus labels
          val labels = OperatorMetrics.labels(
            event,
            ctx.resourceType.resourceType,
            ctx.namespace.orElse(event.namespace)
          )

          // Increasing the event counter
          operatorMetrics.eventCounter.inc(labels).ignore *>
            // Running the event processor
            f(ctx, event).timed.flatMap {
              case (duration, result) =>
                // Recording the event processing time
                operatorMetrics.eventProcessingTime
                  .observe(
                    duration.toMillis.toDouble / 1000.0,
                    labels
                  )
                  .ignore
                  .as(result)
            }
        }
    }

def operator3(metrics: OperatorMetrics) = 
    Operator.namespaced(
        eventProcessor @@ logEvents @@ metered(metrics)
    )(namespace = None, buffer = 1024)
```

To learn more about the general idea of _aspects_, check [the presentation of Adam Fraser](https://www.youtube.com/watch?v=gcqWdNwNEPg) from ZIO Meetup London on September 24, 2020.