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
import zio.{ Cause, IO, Schedule, Scope, ZIO, ZLayer }

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
  ): ZIO[Scope, LeaderElectionFailure[Nothing], Unit] =
    for {
      alreadyOwned <- checkIfAlreadyOwned(namespace, self)
      lock         <-
        if (alreadyOwned)
          ZIO
            .logAnnotate("name", "Leader") {
              ZIO.logInfo(
                s"Lock '$lockName' in namespace '${namespace.value}' is already owned by the current pod"
              )
            }
        else
          ZIO.acquireRelease(
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
  ): ZIO[Any, LeaderElectionFailure[Nothing], Unit] =
    ZIO.logAnnotate("name", "Leader") {
      val podName = self.metadata.flatMap(_.name).getOrElse("(unknown name)")
      for {
        _               <-
          ZIO.logInfo(s"Pod $podName acquiring lock '$lockName' in namespace '${namespace.value}'")
        lock            <- makeLock(lockName, namespace, self)
        finalRetryPolicy = retryPolicy && Schedule.recurWhileZIO[Any, K8sFailure] {
                             case DecodedFailure(_, status, code)
                                 if status.reason.contains("AlreadyExists") =>
                               ZIO.logInfo(s"Lock is already taken, retrying...").as(true)
                             case _ =>
                               ZIO
                                 .logInfo(s"Pod $podName failed to acquire lock '$lockName''")
                                 .as(false)
                           }
        _               <- client
                             .create(lock, Some(namespace))
                             .retry(finalRetryPolicy)
                             .mapError(KubernetesError.apply)
        _               <- ZIO.logInfo(s"Pod $podName successfully acquired lock '$lockName'")
      } yield ()
    }

  private def deleteLock(
    lockName: String,
    namespace: K8sNamespace
  ): ZIO[Any, Nothing, Unit] =
    ZIO.logAnnotate("name", "Leader") {
      if (deleteOnRelease) {
        ZIO.logInfo(s"Releasing lock '$lockName' in namespace '${namespace.value}'") *>
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
        ZIO.logInfo(
          s"Keeping unused lock '$lockName' in namespace '${namespace.value}' to be released by pod deletion"
        )
      }
    }
}
