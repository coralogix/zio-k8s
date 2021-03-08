package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.{ DecodedFailure, K8sFailure }
import com.coralogix.zio.k8s.client.K8sFailure.syntax._
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.{ configmaps, pods }
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.model.core.v1.{ ConfigMap, Pod }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, ObjectMeta }
import com.coralogix.zio.k8s.operator.OperatorFailure.k8sFailureToThrowable
import com.coralogix.zio.k8s.operator.OperatorLogging.logFailure
import zio._
import zio.blocking.Blocking
import zio.clock._
import zio.duration._
import zio.logging._
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.system._

import java.io.IOException

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
object Leader {

  /** Default retry policy for acquiring the lock
    */
  val defaultRetryPolicy: Schedule[Any, Any, Unit] =
    (Schedule.exponential(base = 1.second, factor = 2.0) || Schedule.spaced(30.seconds)).unit

  /** Runs the given effect by applying the leader election algorithm, with the guarantee that
    * the inner effect will only run at once in the Kubernetes cluster.
    *
    * If you want to manage the lock as a ZManaged use [[lease()]]
    *
    * @param lockName Name of the lock
    * @param namespace Namespace to look for the running Pod in
    * @param retryPolicy Retry policy if the lock is already taken
    * @param f Inner effect to protect
    */
  def leaderForLife[R, E, A](
    lockName: String,
    namespace: Option[K8sNamespace] = None,
    retryPolicy: Schedule[Any, K8sFailure, Unit] = defaultRetryPolicy
  )(
    f: ZIO[R, E, A]
  ): ZIO[R with Blocking with System with Clock with Pods with ConfigMaps with Logging, E, Option[
    A
  ]] =
    lease(lockName, namespace, retryPolicy)
      .use(_ => f.bimap(ApplicationError.apply, Some.apply))
      .catchAll((failure: LeaderElectionFailure[E]) => logLeaderElectionFailure(failure).as(None))

  /** Creates a managed lock implementing the leader election algorithm
    * @param lockName Name of the lock
    * @param namespace Namespace to look for the running Pod in
    * @param retryPolicy Retry policy if the lock is already taken
    */
  def lease(
    lockName: String,
    namespace: Option[K8sNamespace] = None,
    retryPolicy: Schedule[Any, K8sFailure, Unit] = defaultRetryPolicy
  ): ZManaged[
    Blocking with System with Clock with Pods with ConfigMaps with Logging,
    LeaderElectionFailure[Nothing],
    Unit
  ] =
    for {
      namespace   <- getNamespace(namespace).toManaged_
      pod         <- getPod(namespace).toManaged_
      managedLock <- acquireLock(lockName, namespace, pod, retryPolicy)
    } yield managedLock

  def logLeaderElectionFailure[E](
    failure: LeaderElectionFailure[E]
  ): ZIO[Logging, E, Unit] =
    log.locally(LogAnnotation.Name("Leader" :: Nil)) {
      failure match {
        case UnknownNamespace(Some(reason)) =>
          log.throwable(s"Could not read namespace", reason)
        case UnknownNamespace(None)         =>
          log.error(s"Could not read namespace")
        case PodNameMissing(Some(reason))   =>
          log.throwable(s"Could not read the POD_NAME environment variable", reason)
        case PodNameMissing(None)           =>
          log.error(s"The POD_NAME environment variable is missing")
        case KubernetesError(error)         =>
          logFailure(s"Kubernetes failure", Cause.fail(error))
        case ApplicationError(error)        =>
          ZIO.fail(error)
      }
    }

  private def getNamespace(
    providedNamespace: Option[K8sNamespace]
  ): ZIO[Blocking, LeaderElectionFailure[Nothing], K8sNamespace] =
    providedNamespace match {
      case Some(value) => ZIO.succeed(value)
      case None        =>
        Files
          .readAllLines(Path("/var/run/secrets/kubernetes.io/serviceaccount/namespace"))
          .bimap(error => UnknownNamespace(Some(error)), _.headOption)
          .flatMap {
            case Some(line) => ZIO.succeed(K8sNamespace(line.trim))
            case None       => ZIO.fail(UnknownNamespace(None))
          }
    }

  private def getPod(
    namespace: K8sNamespace
  ): ZIO[Pods with System, LeaderElectionFailure[Nothing], Pod] =
    system
      .env("POD_NAME")
      .mapError(reason => PodNameMissing(Some(reason)))
      .flatMap {
        case Some(podName) =>
          pods
            .get(podName, namespace)
            .mapError(KubernetesError.apply)
        case None          =>
          ZIO.fail(PodNameMissing(None))
      }

  private def checkIfAlreadyOwned(
    lockName: String,
    namespace: K8sNamespace,
    self: Pod
  ): ZIO[ConfigMaps, LeaderElectionFailure[Nothing], Boolean] =
    for {
      lockResource <- configmaps.get(lockName, namespace).ifFound.mapError(KubernetesError.apply)
      owned         = lockResource match {
                        case Some(lockResource) =>
                          val singleOwner = lockResource.metadata
                            .flatMap(_.ownerReferences)
                            .getOrElse(Vector.empty)
                            .size == 1
                          singleOwner && lockResource.isOwnedBy(self)
                        case None               =>
                          false
                      }
    } yield owned

  private def makeLock(
    lockName: String,
    namespace: K8sNamespace,
    self: Pod
  ): IO[LeaderElectionFailure[Nothing], ConfigMap] =
    ConfigMap(
      metadata = Some(
        ObjectMeta(
          name = Some(lockName),
          namespace = Some(namespace.value)
        )
      )
    ).tryAttachOwner(self).mapError(KubernetesError.apply)

  private def tryCreateLock(
    lockName: String,
    namespace: K8sNamespace,
    self: Pod,
    retryPolicy: Schedule[Any, K8sFailure, Unit]
  ): ZIO[ConfigMaps with Clock with Logging, LeaderElectionFailure[Nothing], Unit] =
    log.locally(LogAnnotation.Name("Leader" :: Nil)) {
      for {
        _               <- log.info(s"Acquiring lock '$lockName' in namespace '${namespace.value}'")
        lock            <- makeLock(lockName, namespace, self)
        finalRetryPolicy = retryPolicy && Schedule.recurWhileM[Logging, K8sFailure] {
                             case DecodedFailure(status, code)
                                 if status.reason.contains("AlreadyExists") =>
                               log.info(s"Lock is already taken, retrying...").as(true)
                             case _ =>
                               ZIO.succeed(false)
                           }
        _               <- configmaps
                             .create(lock, namespace)
                             .retry(finalRetryPolicy)
                             .mapError(KubernetesError.apply)
      } yield ()
    }

  private def deleteLock(
    lockName: String,
    namespace: K8sNamespace
  ): ZIO[ConfigMaps with Logging, Nothing, Unit] =
    log.locally(LogAnnotation.Name("Leader" :: Nil)) {
      log.info(s"Releasing lock '$lockName' in namespace '${namespace.value}'") *>
        configmaps
          .delete(lockName, DeleteOptions(), namespace)
          .unit
          .catchAll { (failure: K8sFailure) =>
            logFailure(
              s"Failed to delete lock '$lockName' in namespace '${namespace.value}'",
              Cause.fail(failure)
            )
          }
    }

  private def acquireLock(
    lockName: String,
    namespace: K8sNamespace,
    self: Pod,
    retryPolicy: Schedule[Any, K8sFailure, Unit]
  ): ZManaged[ConfigMaps with Clock with Logging, LeaderElectionFailure[Nothing], Unit] =
    for {
      alreadyOwned <- checkIfAlreadyOwned(lockName, namespace, self).toManaged_
      lock         <-
        if (alreadyOwned)
          log
            .locally(LogAnnotation.Name("Leader" :: Nil)) {
              log
                .info(
                  s"Lock '$lockName' in namespace '${namespace.value}' is already owned by the current pod"
                )
            }
            .toManaged_ *>
            ZManaged.make(ZIO.unit)(_ => deleteLock(lockName, namespace))
        else
          ZManaged.make(
            tryCreateLock(lockName, namespace, self, retryPolicy)
          )(_ => deleteLock(lockName, namespace))
    } yield lock

  /** Possible failures of the leader election algorithm
    * @tparam E Failure type of the inner effect
    */
  sealed trait LeaderElectionFailure[+E]

  /** Could not determine the namespace
    *
    * If it is not provided by the caller, the implementation tries to read it from
    * /var/run/secrets/kubernetes.io/serviceaccount/namespace.
    */
  final case class UnknownNamespace(reason: Option[IOException])
      extends LeaderElectionFailure[Nothing]

  /** Could not determine the running Pod's name. It has to be provided in the POD_NAME environment variable.
    */
  final case class PodNameMissing(reason: Option[SecurityException])
      extends LeaderElectionFailure[Nothing]

  /** Failure while calling the Kubernetes API
    */
  final case class KubernetesError(error: K8sFailure) extends LeaderElectionFailure[Nothing]

  /** Inner effect failed
    */
  final case class ApplicationError[E](error: E) extends LeaderElectionFailure[E]
}
