package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.coordination.v1.leases.Leases
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.operator.OperatorFailure.k8sFailureToThrowable
import com.coralogix.zio.k8s.operator.OperatorLogging.logFailure
import com.coralogix.zio.k8s.operator.OperatorLogging.ConvertableToThrowable._
import com.coralogix.zio.k8s.operator.contextinfo.{ ContextInfo, ContextInfoFailure }
import com.coralogix.zio.k8s.operator.leader.locks.leaderlockresources.LeaderLockResources
import com.coralogix.zio.k8s.operator.leader.locks.{ ConfigMapLock, CustomLeaderLock, LeaseLock }
import zio.clock.Clock
import zio.duration.{ durationInt, Duration }
import zio.logging.{ log, LogAnnotation, Logging }
import zio.random.Random
import zio.{ Cause, Has, Queue, Schedule, ZIO, ZLayer, ZManaged }

import java.time.DateTimeException

package object leader {
  type LeaderElection = Has[LeaderElection.Service]
  object LeaderElection {
    trait Service {

      /** Runs the given effect by applying the leader election algorithm, with the guarantee that
        * the inner effect will only run at once in the Kubernetes cluster.
        *
        * If you want to manage the lock as a ZManaged use [[lease]]
        *
        * @param f Inner effect to protect
        */
      def runAsLeader[R, E, A](f: ZIO[R, E, A]): ZIO[R with Clock with Logging, E, Option[A]] =
        lease
          .use(_ => f.bimap(ApplicationError.apply, Some.apply))
          .catchAll((failure: LeaderElectionFailure[E]) =>
            logLeaderElectionFailure(failure).as(None)
          )

      /** Creates a managed lock implementing the leader election algorithm
        */
      def lease: ZManaged[Clock with Logging, LeaderElectionFailure[Nothing], Unit]
    }

    class Live(contextInfo: ContextInfo.Service, lock: LeaderLock) extends Service {
      override def lease: ZManaged[Clock with Logging, LeaderElectionFailure[Nothing], Unit] =
        for {
          namespace   <- contextInfo.namespace.toManaged_.mapError(ContextInfoError)
          pod         <- contextInfo.pod.toManaged_.mapError(ContextInfoError)
          managedLock <- lock.acquireLock(namespace, pod)
        } yield managedLock

    }

    class LiveTemporary(
      contextInfo: ContextInfo.Service,
      lock: LeaderLock,
      leadershipLost: Queue[Unit]
    ) extends Service {
      override def lease: ZManaged[Clock with Logging, LeaderElectionFailure[Nothing], Unit] =
        for {
          namespace   <- contextInfo.namespace.toManaged_.mapError(ContextInfoError)
          pod         <- contextInfo.pod.toManaged_.mapError(ContextInfoError)
          managedLock <- lock.acquireLock(namespace, pod)
        } yield managedLock

      override def runAsLeader[R, E, A](
        f: ZIO[R, E, A]
      ): ZIO[R with Clock with Logging, E, Option[A]] =
        lease
          .use { _ =>
            f.bimap(ApplicationError.apply, Some.apply) raceFirst leadershipLost.take.as(None)
          }
          .catchAll((failure: LeaderElectionFailure[E]) =>
            logLeaderElectionFailure(failure).as(None)
          )
    }

    /** Default retry policy for acquiring the lock
      */
    val defaultRetryPolicy: Schedule[Any, Any, Unit] =
      (Schedule.exponential(base = 1.second, factor = 2.0) || Schedule.spaced(30.seconds)).unit

    /** Constructs a leader election interface using a given [[LeaderLock]] layer
      *
      * For built-in leader election algorithms check [[configMapLock()]] and [[customLeaderLock()]].
      */
    def fromLock: ZLayer[Has[LeaderLock] with ContextInfo, Nothing, LeaderElection] =
      (for {
        selfInfo <- ZIO.service[ContextInfo.Service]
        lock     <- ZIO.service[LeaderLock]
      } yield new Live(selfInfo, lock)).toLayer

    /** Simple leader election implementation
      *
      * The algorithm tries creating a ConfigMap with a given name and attaches the Pod it
      * is running in as an owner of the config map.
      *
      * If the ConfigMap already exists the leader election fails and retries with exponential backoff.
      * If it succeeds then it runs the inner effect.
      *
      * When the code terminates normally the acquired ConfigMap gets released. If the whole Pod gets
      * killed without releasing the resource, the registered ownership will make Kubernetes apply
      * cascading deletion so eventually a new Pod can register the ConfigMap again.
      */
    def configMapLock(
      lockName: String,
      retryPolicy: Schedule[Any, Any, Unit] = defaultRetryPolicy,
      deleteLockOnRelease: Boolean = true
    ): ZLayer[ContextInfo with ConfigMaps with Pods, Nothing, LeaderElection] =
      (ContextInfo.any ++
        ZLayer.fromService[ConfigMaps.Service, LeaderLock](configmaps =>
          new ConfigMapLock(lockName, retryPolicy, deleteLockOnRelease, configmaps)
        )) >>> fromLock

    /** Simple leader election implementation based on a custom resource
      *
      * The algorithm tries creating a LeaderLock resource with a given name and attaches the Pod it
      * is running in as an owner of the config map.
      *
      * If the LeaderLock already exists the leader election fails and retries with exponential backoff.
      * If it succeeds then it runs the inner effect.
      *
      * When the code terminates normally the acquired LeaderLock gets released. If the whole Pod gets
      * killed without releasing the resource, the registered ownership will make Kubernetes apply
      * cascading deletion so eventually a new Pod can register the LeaderLock again.
      *
      * This method requires the registration of the LeaderLock custom resource. As an alternative take
      * a look at [[configMapLock()]].
      */
    def customLeaderLock(
      lockName: String,
      retryPolicy: Schedule[Any, Any, Unit] = defaultRetryPolicy,
      deleteLockOnRelease: Boolean = true
    ): ZLayer[ContextInfo with LeaderLockResources with Pods, Nothing, LeaderElection] =
      (ContextInfo.any ++
        ZLayer.fromService[LeaderLockResources.Service, LeaderLock](configmaps =>
          new CustomLeaderLock(lockName, retryPolicy, deleteLockOnRelease, configmaps)
        )) >>> fromLock

    /** Lease based leader election implementation
      *
      * The leadership is not guaranteed to be held forever, the effect executed in runAsLeader
      * may be interrupted. It is recommended to retry runAsLeader in these cases to try to reacquire the lease.
      *
      * This is a reimplementation of the Go leaderelection package:
      * https://github.com/kubernetes/client-go/blob/master/tools/leaderelection/leaderelection.go
      *
      * @param lockName Name of the lease resource
      * @param leaseDuration Duration non-leader candidates must wait before acquiring leadership. This is measured
      *                      against the time of the last observed change.
      * @param renewTimeout The maximum time a leader is allowed to try to renew its lease before giving up
      * @param retryPeriod  Retry period for acquiring and renewing the lease
      */
    def leaseLock(
      lockName: String,
      leaseDuration: Duration = 15.seconds,
      renewTimeout: Duration = 10.seconds,
      retryPeriod: Duration = 2.seconds
    ): ZLayer[Random with ContextInfo with Leases, Nothing, LeaderElection] =
      (for {
        selfInfo       <- ZIO.service[ContextInfo.Service]
        leases         <- ZIO.service[Leases.Service]
        random         <- ZIO.service[Random.Service]
        leadershipLost <- Queue.bounded[Unit](1)
        lock            = new LeaseLock(
                            lockName,
                            leases,
                            random,
                            leadershipLost,
                            leaseDuration,
                            renewTimeout,
                            retryPeriod
                          )
      } yield new LiveTemporary(selfInfo, lock, leadershipLost)).toLayer

    private[leader] def logLeaderElectionFailure[E](
      failure: LeaderElectionFailure[E]
    ): ZIO[Logging, E, Unit] =
      log.locally(LogAnnotation.Name("Leader" :: Nil)) {
        failure match {
          case ContextInfoError(error) =>
            logFailure("Failed to gather context info", Cause.fail(error))
          case KubernetesError(error)  =>
            logFailure(s"Kubernetes failure", Cause.fail(error))
          case DateTimeError(error)    =>
            log.throwable(s"Failed to query time", error)
          case ApplicationError(error) =>
            ZIO.fail(error)
        }
      }
  }

  /** Possible failures of the leader election algorithm
    * @tparam E Failure type of the inner effect
    */
  sealed trait LeaderElectionFailure[+E]

  /** Failure while gathering information about the running service
    */
  final case class ContextInfoError(error: ContextInfoFailure)
      extends LeaderElectionFailure[Nothing]

  /** Failure while calling the Kubernetes API
    */
  final case class KubernetesError(error: K8sFailure) extends LeaderElectionFailure[Nothing]

  /** Failure while calling the Date-Time API */
  final case class DateTimeError(error: DateTimeException) extends LeaderElectionFailure[Nothing]

  /** Inner effect failed
    */
  final case class ApplicationError[E](error: E) extends LeaderElectionFailure[E]

  /** Runs the given effect by applying the leader election algorithm, with the guarantee that
    * the inner effect will only run at once in the Kubernetes cluster.
    *
    * If you want to manage the lock as a ZManaged use [[lease()]]
    *
    * @param f Inner effect to protect
    */
  def runAsLeader[R, E, A](
    f: ZIO[R, E, A]
  ): ZIO[R with LeaderElection with Clock with Logging, E, Option[A]] =
    ZIO.accessM(
      _.get.runAsLeader(f)
    )

  /** Creates a managed lock implementing the leader election algorithm
    */
  def lease
    : ZManaged[LeaderElection with Clock with Logging, LeaderElectionFailure[Nothing], Unit] =
    ZManaged.accessManaged(_.get.lease)
}
