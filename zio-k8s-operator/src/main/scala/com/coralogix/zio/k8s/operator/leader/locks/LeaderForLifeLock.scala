package com.coralogix.zio.k8s.operator.leader.locks

import com.coralogix.zio.k8s.client.K8sFailure.syntax.K8sZIOSyntax
import com.coralogix.zio.k8s.client.model.K8sObject._
import com.coralogix.zio.k8s.client.model.{ K8sNamespace, K8sObject }
import com.coralogix.zio.k8s.client.{ DecodedFailure, K8sFailure, Resource, ResourceDelete }
import com.coralogix.zio.k8s.model.core.v1.Pod
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, Status }
import com.coralogix.zio.k8s.operator.OperatorFailure.k8sFailureToThrowable
import com.coralogix.zio.k8s.operator.OperatorLogging.logFailure
import com.coralogix.zio.k8s.operator.leader.{ KubernetesError, LeaderElectionFailure, LeaderLock }
import zio.clock.Clock
import zio.logging.{ log, LogAnnotation, Logging }
import zio.{ Cause, IO, Schedule, ZIO, ZManaged }

abstract class LeaderForLifeLock[T: K8sObject](
  lockName: String,
  retryPolicy: Schedule[Any, K8sFailure, Unit],
  deleteOnRelease: Boolean = true
) extends LeaderLock {

  /** Resource client to get/create lock resources */
  protected def client: Resource[T]

  /** Resource client to delete lock resources */
  protected def clientDelete: ResourceDelete[T, Status]

  /** Creates a lock resource.
    *
    * It must have its metadata field initialized (at least to an empty ObjectMeta)
    */
  protected def makeLock: T

  private def makeLock(
    lockName: String,
    namespace: K8sNamespace,
    self: Pod
  ): IO[LeaderElectionFailure[Nothing], T] =
    makeLock
      .mapMetadata(
        _.copy(
          name = Some(lockName),
          namespace = Some(namespace.value)
        )
      )
      .tryAttachOwner(self)
      .mapError(KubernetesError.apply)

  def acquireLock(
    namespace: K8sNamespace,
    self: Pod
  ): ZManaged[Clock with Logging, LeaderElectionFailure[Nothing], Unit] =
    for {
      alreadyOwned <- checkIfAlreadyOwned(namespace, self).toManaged_
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
            tryCreateLock(namespace, self)
          )(_ => deleteLock(lockName, namespace))
    } yield lock

  private def checkIfAlreadyOwned(
    namespace: K8sNamespace,
    self: Pod
  ): IO[LeaderElectionFailure[Nothing], Boolean] =
    for {
      lockResource <- client.get(lockName, Some(namespace)).ifFound.mapError(KubernetesError.apply)
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

  private def tryCreateLock(
    namespace: K8sNamespace,
    self: Pod
  ): ZIO[Clock with Logging, LeaderElectionFailure[Nothing], Unit] =
    log.locally(LogAnnotation.Name("Leader" :: Nil)) {
      for {
        _               <- log.info(s"Acquiring lock '$lockName' in namespace '${namespace.value}'")
        lock            <- makeLock(lockName, namespace, self)
        finalRetryPolicy = retryPolicy && Schedule.recurWhileM[Logging, K8sFailure] {
                             case DecodedFailure(_, status, code)
                                 if status.reason.contains("AlreadyExists") =>
                               log.info(s"Lock is already taken, retrying...").as(true)
                             case _ =>
                               ZIO.succeed(false)
                           }
        _               <- client
                             .create(lock, Some(namespace))
                             .retry(finalRetryPolicy)
                             .mapError(KubernetesError.apply)
      } yield ()
    }

  private def deleteLock(
    lockName: String,
    namespace: K8sNamespace
  ): ZIO[Logging, Nothing, Unit] =
    log.locally(LogAnnotation.Name("Leader" :: Nil)) {
      if (deleteOnRelease) {
        log.info(s"Releasing lock '$lockName' in namespace '${namespace.value}'") *>
          clientDelete
            .delete(lockName, DeleteOptions(), Some(namespace))
            .unit
            .catchAll { (failure: K8sFailure) =>
              logFailure(
                s"Failed to delete lock '$lockName' in namespace '${namespace.value}'",
                Cause.fail(failure)
              )
            }
      } else {
        log.info(
          s"Keeping unused lock '$lockName' in namespace '${namespace.value}' to be released by pod deletion"
        )
      }
    }
}
