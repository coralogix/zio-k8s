package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.model.{
  K8sNamespace,
  K8sResourceType,
  ResourceMetadata,
  TypedWatchEvent
}
import com.coralogix.zio.k8s.client.{ ClusterResource, K8sFailure, NamespacedResource, NotFound }
import com.coralogix.zio.k8s.operator.Operator.OperatorContext
import com.coralogix.zio.k8s.operator.OperatorLogging._
import izumi.reflect.Tag
import zio._
import zio.clock.Clock
import zio.duration.durationInt
import zio.logging.{ log, Logging }
import zio.stream.ZStream

/** Core implementation of the operator logic.
  * Watches a stream and calls an event processor.
  *
  * An instance of this is tied to one particular resource type in one namespace.
  */
trait Operator[R, E, T] {
  protected def watchStream(): ZStream[Clock, K8sFailure, TypedWatchEvent[T]]

  def processEvent(event: TypedWatchEvent[T]): ZIO[R, OperatorFailure[E], Unit]

  val context: OperatorContext
  val bufferSize: Int

  implicit def toThrowable: ConvertableToThrowable[E] =
    (error: E) => new RuntimeException(s"Operator failure: $error")

  /** Starts the operator on a forked fiber
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
          log.locally(OperatorLogging(context)) {
            log.error(s"Watch stream terminated")
          } *> ZIO.dieMessage("Watch stream should never terminate")
      )
      .repeat(
        (Schedule.exponential(base = 1.second, factor = 2.0) ||
          Schedule.spaced(30.seconds)).unit
      )
      .fork
}

abstract class NamespacedOperator[R, E, T](
  client: NamespacedResource[T],
  namespace: Option[K8sNamespace]
) extends Operator[R, E, T] {
  override protected def watchStream(): ZStream[Clock, K8sFailure, TypedWatchEvent[T]] =
    client.watchForever(namespace)
}

abstract class ClusterOperator[R, E, T](
  client: ClusterResource[T]
) extends Operator[R, E, T] {
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

  type EventProcessor[R, E, T] =
    (OperatorContext, TypedWatchEvent[T]) => ZIO[R, OperatorFailure[E], Unit]

  def namespaced[R: Tag, E, T: Tag: ResourceMetadata](
    eventProcessor: EventProcessor[R, E, T]
  )(
    namespace: Option[K8sNamespace],
    buffer: Int
  ): ZIO[Has[NamespacedResource[T]], Nothing, Operator[R, E, T]] =
    ZIO.service[NamespacedResource[T]].map { client =>
      val ctx = OperatorContext(implicitly[ResourceMetadata[T]].resourceType, namespace)
      new NamespacedOperator[R, E, T](client, namespace) {
        override def processEvent(event: TypedWatchEvent[T]): ZIO[R, OperatorFailure[E], Unit] =
          eventProcessor(ctx, event)
        override val context: OperatorContext = ctx
        override val bufferSize: Int = buffer
      }
    }

  def cluster[R: Tag, E, T: Tag: ResourceMetadata](
    eventProcessor: EventProcessor[R, E, T]
  )(buffer: Int): ZIO[Has[ClusterResource[T]], Nothing, Operator[R, E, T]] =
    ZIO.service[ClusterResource[T]].map { client =>
      val ctx = OperatorContext(implicitly[ResourceMetadata[T]].resourceType, None)
      new ClusterOperator[R, E, T](client) {
        override def processEvent(event: TypedWatchEvent[T]): ZIO[R, OperatorFailure[E], Unit] =
          eventProcessor(ctx, event)
        override val context: OperatorContext = ctx
        override val bufferSize: Int = buffer
      }
    }

  /** Event processor aspect */
  trait Aspect[-R, +E, T] { self =>
    def apply[R1 <: R, E1 >: E](
      f: EventProcessor[R1, E1, T]
    ): EventProcessor[R1, E1, T]

    final def >>>[R1 <: R, E1 >: E](that: Aspect[R1, E1, T]): Aspect[R1, E1, T] =
      andThen(that)

    final def andThen[R1 <: R, E1 >: E](that: Aspect[R1, E1, T]): Aspect[R1, E1, T] =
      new Aspect[R1, E1, T] {
        override def apply[R2 <: R1, E2 >: E1](
          f: EventProcessor[R2, E2, T]
        ): EventProcessor[R2, E2, T] =
          that(self(f))
      }
  }

  implicit class EventProcessorOps[R, E, T](
    eventProcessor: EventProcessor[R, E, T]
  ) {
    def @@[R1 <: R, E1 >: E](aspect: Aspect[R1, E1, T]): EventProcessor[R1, E1, T] =
      aspect[R1, E1](eventProcessor)
  }
}
