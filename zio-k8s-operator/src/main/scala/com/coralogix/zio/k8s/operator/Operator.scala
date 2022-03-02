package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.model.{
  K8sNamespace,
  K8sResourceType,
  ResourceMetadata,
  TypedWatchEvent
}
import com.coralogix.zio.k8s.client.{ ClusterResource, K8sFailure, NamespacedResource, NotFound }
import com.coralogix.zio.k8s.operator.Operator.{ EventProcessor, OperatorContext }
import com.coralogix.zio.k8s.operator.OperatorLogging._
import zio.ZIO._
import zio.stream.ZStream
import zio.{ Clock, _ }

/** Core implementation of the operator logic. Watches a stream and calls an event processor.
  *
  * An instance of this is tied to one particular resource type in one namespace.
  *
  * Create an instance using either [[Operator.namespaced()]] or [[Operator.cluster()]]
  */
trait Operator[R, E, T] { self =>
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
      .mapZIO(processEvent)
      .runDrain
      .foldCauseZIO(
        cause =>
          if (cause.failureOption.contains(KubernetesFailure(NotFound))) {
            logSpan(OperatorLogging(context)) {
              logInfo("Watched resource is not available yet")
            }
          } else {
            locally(OperatorLogging(context)) {
              logFailure(s"Watch stream failed", cause)
            }
          },
        _ =>
          locally(OperatorLogging(context)) {
            logError(s"Watch stream terminated")
          } *> ZIO.dieMessage("Watch stream should never terminate")
      )
      .repeat(
        (Schedule.exponential(base = 1.second, factor = 2.0) ||
          Schedule.spaced(30.seconds)).unit
      )
      .fork

  /** Modify the operator's event processor with the given function
    */
  def mapEventProcessor[R1, E1](
    f: ZIO[R, OperatorFailure[E], Unit] => ZIO[R1, OperatorFailure[E1], Unit]
  ): Operator[R1, E1, T]

  /** Provide the required environment for the operator
    */
  final def provide(r: R)(implicit ev: NeedsEnv[R]): Operator[Any, E, T] =
    provideSome(_ => r)

  /** Provide a part of the required environment for the operator
    */
  final def provideSome[R0](f: R0 => R)(implicit ev: NeedsEnv[R]): Operator[R0, E, T] =
    mapEventProcessor(_.provideSome[R0](f))

  /** Provide the required environment for the operator with a layer
    */
  final def provideLayer[E1 >: E, R0, R1](
    layer: ZLayer[R0, OperatorFailure[E1], R]
  )(implicit ev1: R1 <:< R, ev2: NeedsEnv[R]): Operator[R0, E1, T] =
    mapEventProcessor(_.provideLayer(layer))

  /** Provide the required environment for the operator with a layer on top of the standard ones
    */
  final def provideCustomLayer[E1 >: E, R1 <: ZEnvironment[_]](
    layer: ZLayer[ZEnv, OperatorFailure[E1], R1]
  )(implicit ev: ZEnv with R1 <:< R, tagged: EnvironmentTag[R1]): Operator[ZEnv, E1, T] =
    mapEventProcessor(_.provideCustomLayer(layer))

  /** Provide parts of the required environment for the operator with a layer
    */
  final def provideSomeLayer[R0 <: ZEnvironment[_]]: Operator.ProvideSomeLayer[R0, R, E, T] =
    new Operator.ProvideSomeLayer[R0, R, E, T](self)
}

final class NamespacedOperator[R, E, T](
  client: NamespacedResource[T],
  namespace: Option[K8sNamespace],
  eventProcessor: EventProcessor[R, E, T],
  override val context: OperatorContext,
  override val bufferSize: Int
) extends Operator[R, E, T] {
  override protected def watchStream(): ZStream[Clock, K8sFailure, TypedWatchEvent[T]] =
    client.watchForever(namespace)

  override def processEvent(event: TypedWatchEvent[T]): ZIO[R, OperatorFailure[E], Unit] =
    eventProcessor(context, event)

  override def mapEventProcessor[R1, E1](
    f: ZIO[R, OperatorFailure[E], Unit] => ZIO[R1, OperatorFailure[E1], Unit]
  ): Operator[R1, E1, T] =
    new NamespacedOperator[R1, E1, T](
      client,
      namespace,
      (ctx: OperatorContext, evt: TypedWatchEvent[T]) => f(eventProcessor(ctx, evt)),
      context,
      bufferSize
    )
}

final class ClusterOperator[R, E, T](
  client: ClusterResource[T],
  eventProcessor: EventProcessor[R, E, T],
  override val context: OperatorContext,
  override val bufferSize: Int
) extends Operator[R, E, T] {
  override protected def watchStream(): ZStream[Clock, K8sFailure, TypedWatchEvent[T]] =
    client.watchForever()

  override def processEvent(event: TypedWatchEvent[T]): ZIO[R, OperatorFailure[E], Unit] =
    eventProcessor(context, event)

  override def mapEventProcessor[R1, E1](
    f: ZIO[R, OperatorFailure[E], Unit] => ZIO[R1, OperatorFailure[E1], Unit]
  ): Operator[R1, E1, T] =
    new ClusterOperator[R1, E1, T](
      client,
      (ctx: OperatorContext, evt: TypedWatchEvent[T]) => f(eventProcessor(ctx, evt)),
      context,
      bufferSize
    )
}

object Operator {

  /** Static contextual information for the event processors, usable for implementing generic
    * loggers/metrics etc.
    */
  case class OperatorContext(resourceType: K8sResourceType, namespace: Option[K8sNamespace]) {
    def withSpecificNamespace(namespace: Option[K8sNamespace]): OperatorContext =
      this.namespace match {
        case None    => copy(namespace = namespace)
        case Some(_) => this
      }
  }

  /** Operator event processor
    *
    * This is the type to be implemented when writing operators using the zio-k8s-operator library.
    * @tparam R
    *   Operator environment
    * @tparam E
    *   Operator-specific error type
    * @tparam T
    *   Resource type
    */
  trait EventProcessor[-R, +E, T] { self =>

    /** Process an incoming event
      * @param context
      *   Information about the operator
      * @param event
      *   Event to be processed
      */
    def apply(context: OperatorContext, event: TypedWatchEvent[T]): ZIO[R, OperatorFailure[E], Unit]

    /** Applies an aspect to the event processor
      */
    def @@[R1 <: R, E1 >: E](aspect: Aspect[R1, E1, T]): EventProcessor[R1, E1, T] =
      aspect[R1, E1](self)
  }

  /** Creates an operator for a namespaced resource
    * @param eventProcessor
    *   Event processor implementation
    * @param namespace
    *   Namespace to run in. If None, it will watch resources from all namespaces.
    * @param buffer
    *   Buffer size for the incoming events
    * @tparam R
    *   Operator environment
    * @tparam E
    *   Operator-specific error type
    * @tparam T
    *   Resource type
    * @return
    *   An operator that can be run with [[Operator.start()]]
    */
  def namespaced[R: EnvironmentTag, E, T: EnvironmentTag: ResourceMetadata](
    eventProcessor: EventProcessor[R, E, T]
  )(
    namespace: Option[K8sNamespace],
    buffer: Int
  ): ZIO[NamespacedResource[T], Nothing, Operator[R, E, T]] =
    ZIO.service[NamespacedResource[T]].map { client =>
      val ctx = OperatorContext(implicitly[ResourceMetadata[T]].resourceType, namespace)
      new NamespacedOperator[R, E, T](client, namespace, eventProcessor, ctx, buffer)
    }

  /** Creates an operator for a cluster resource
    * @param eventProcessor
    *   Event processor implementation
    * @param buffer
    *   Buffer size for the incoming events
    * @tparam R
    *   Operator environment
    * @tparam E
    *   Operator-specific error type
    * @tparam T
    *   Resource type
    * @return
    *   An operator that can be run with [[Operator.start()]]
    */
  def cluster[R: EnvironmentTag, E, T: EnvironmentTag: ResourceMetadata](
    eventProcessor: EventProcessor[R, E, T]
  )(buffer: Int): ZIO[ClusterResource[T], Nothing, Operator[R, E, T]] =
    ZIO.service[ClusterResource[T]].map { client =>
      val ctx = OperatorContext(implicitly[ResourceMetadata[T]].resourceType, None)
      new ClusterOperator[R, E, T](client, eventProcessor, ctx, buffer)
    }

  /** Event processor aspect */
  trait Aspect[-R, +E, T] { self =>
    def apply[R1 <: R, E1 >: E](
      f: EventProcessor[R1, E1, T]
    ): EventProcessor[R1, E1, T]

    /** Alias for [[andThen()]]
      */
    final def >>>[R1 <: R, E1 >: E](that: Aspect[R1, E1, T]): Aspect[R1, E1, T] =
      andThen(that)

    /** Apply this aspect first and then the other one
      */
    final def andThen[R1 <: R, E1 >: E](that: Aspect[R1, E1, T]): Aspect[R1, E1, T] =
      new Aspect[R1, E1, T] {
        override def apply[R2 <: R1, E2 >: E1](
          f: EventProcessor[R2, E2, T]
        ): EventProcessor[R2, E2, T] =
          that(self(f))
      }
  }

  final class ProvideSomeLayer[R0 <: ZEnvironment[_], R, E, T](private val self: Operator[R, E, T])
      extends AnyVal {
    def apply[E1 >: E, R1 <: ZEnvironment[_]](
      layer: ZLayer[R0, OperatorFailure[E1], R1]
    )(implicit
      ev1: R0 with R1 <:< R,
      ev2: NeedsEnv[R],
      tagged: EnvironmentTag[R1]
    ): Operator[R0, E1, T] =
      self.mapEventProcessor(
        _.provideLayer[OperatorFailure[E1], R0, R0 with R1](ZLayer.environment[R0] ++ layer)
      )
  }
}
