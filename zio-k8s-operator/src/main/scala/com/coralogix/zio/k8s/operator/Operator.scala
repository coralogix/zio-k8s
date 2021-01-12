package com.coralogix.operator.logic

import com.coralogix.operator
import zio.k8s.client.model.{
  K8sNamespace,
  K8sResourceType,
  Object,
  ResourceMetadata,
  TypedWatchEvent
}
import zio.k8s.client.{
  ClusterResource,
  ClusterResourceStatus,
  K8sFailure,
  NamespacedResource,
  NamespacedResourceStatus,
  NotFound
}
import com.coralogix.operator.logging.{ logFailure, OperatorLogging }
import com.coralogix.operator.logic.Operator.OperatorContext
import izumi.reflect.Tag
import zio.clock.Clock
import zio.duration.durationInt
import zio.logging.{ log, Logging }
import zio.stream.ZStream
import zio._

/**
  * Core implementation of the operator logic.
  * Watches a stream and calls an event processor.
  *
  * An instance of this is tied to one particular resource type in one namespace.
  */
trait Operator[R, T <: Object] {
  protected def watchStream(): ZStream[Clock, K8sFailure, TypedWatchEvent[T]]

  def processEvent(event: TypedWatchEvent[T]): ZIO[R, OperatorFailure, Unit]

  val context: OperatorContext
  val bufferSize: Int

  /**
    * Starts the operator on a forked fiber
    */
  def start(): URIO[R with Clock with Logging, Fiber.Runtime[Nothing, Unit]] =
    watchStream()
      .buffer(bufferSize)
      .mapError(KubernetesFailure.apply)
      .mapM(processEvent)
      .runDrain
      .foldCauseM(
        {
          case Cause.Fail(KubernetesFailure(NotFound)) =>
            log.locally(OperatorLogging(context)) {
              log.info("Watched resource is not available yet")
            }
          case failure =>
            log.locally(OperatorLogging(context)) {
              logFailure(s"Watch stream failed", failure)
            }
        },
        _ =>
          log.locally(operator.logging.OperatorLogging(context)) {
            log.error(s"Watch stream terminated")
          } *> ZIO.dieMessage("Watch stream should never terminate")
      )
      .repeat(
        (Schedule.exponential(base = 1.second, factor = 2.0) ||
          Schedule.spaced(30.seconds)).unit
      )
      .fork
}

abstract class NamespacedOperator[R, T <: Object](
  client: NamespacedResource[T],
  namespace: Option[K8sNamespace]
) extends Operator[R, T] {
  override protected def watchStream(): ZStream[Clock, K8sFailure, TypedWatchEvent[T]] =
    client.watchForever(namespace)
}

abstract class ClusterOperator[R, T <: Object](
  client: ClusterResource[T]
) extends Operator[R, T] {
  override protected def watchStream(): ZStream[Clock, K8sFailure, TypedWatchEvent[T]] =
    client.watchForever()
}

object Operator {

  /** Static contextual information for the event processors,
    * usable for implementing generic loggers/metrics etc.
    */
  case class OperatorContext(resourceType: K8sResourceType, namespace: Option[K8sNamespace]) {
    def withSpecificNamespace(namespace: Option[K8sNamespace]): OperatorContext =
      this.namespace match {
        case None    => copy(namespace = namespace)
        case Some(_) => this
      }
  }

  type EventProcessor[R, T <: Object] =
    (OperatorContext, TypedWatchEvent[T]) => ZIO[R, OperatorFailure, Unit]

  def namespaced[R: Tag, T <: Object: Tag: ResourceMetadata](
    eventProcessor: EventProcessor[R, T]
  )(
    namespace: Option[K8sNamespace],
    buffer: Int
  ): ZIO[Has[NamespacedResource[T]], Nothing, Operator[R, T]] =
    ZIO.service[NamespacedResource[T]].map { client =>
      val ctx = OperatorContext(implicitly[ResourceMetadata[T]].resourceType, namespace)
      new NamespacedOperator[R, T](client, namespace) {
        override def processEvent(event: TypedWatchEvent[T]): ZIO[R, OperatorFailure, Unit] =
          eventProcessor(ctx, event)
        override val context: OperatorContext = ctx
        override val bufferSize: Int = buffer
      }
    }

  def cluster[R: Tag, T <: Object: Tag: ResourceMetadata](
    eventProcessor: EventProcessor[R, T]
  )(buffer: Int): ZIO[Has[ClusterResource[T]], Nothing, Operator[R, T]] =
    ZIO.service[ClusterResource[T]].map { client =>
      val ctx = OperatorContext(implicitly[ResourceMetadata[T]].resourceType, None)
      new ClusterOperator[R, T](client) {
        override def processEvent(event: TypedWatchEvent[T]): ZIO[R, OperatorFailure, Unit] =
          eventProcessor(ctx, event)
        override val context: OperatorContext = ctx
        override val bufferSize: Int = buffer
      }
    }

  /** Event processor aspect */
  trait Aspect[-R, T <: Object] { self =>
    def apply[R1 <: R](
      f: EventProcessor[R1, T]
    ): EventProcessor[R1, T]

    final def >>>[R1 <: R](that: Aspect[R1, T]): Aspect[R1, T] =
      andThen(that)

    final def andThen[R1 <: R](that: Aspect[R1, T]): Aspect[R1, T] =
      new Aspect[R1, T] {
        override def apply[R2 <: R1](
          f: EventProcessor[R2, T]
        ): EventProcessor[R2, T] =
          that(self(f))
      }
  }

  implicit class EventProcessorOps[R, T <: Object](
    eventProcessor: EventProcessor[R, T]
  ) {
    def @@[R1 <: R](aspect: Aspect[R1, T]): EventProcessor[R1, T] =
      aspect[R1](eventProcessor)
  }
}
