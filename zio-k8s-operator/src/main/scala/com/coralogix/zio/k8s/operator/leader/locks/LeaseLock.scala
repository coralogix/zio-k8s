package com.coralogix.zio.k8s.operator.leader.locks

import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.K8sFailure.syntax._
import com.coralogix.zio.k8s.client.coordination.v1.leases.Leases
import com.coralogix.zio.k8s.client.model.{ K8sNamespace, Optional }
import com.coralogix.zio.k8s.model.coordination.v1.{ Lease, LeaseSpec }
import com.coralogix.zio.k8s.model.core.v1.Pod
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ MicroTime, ObjectMeta }
import com.coralogix.zio.k8s.operator.leader
import com.coralogix.zio.k8s.operator.leader.LeaderElection.logLeaderElectionFailure
import com.coralogix.zio.k8s.operator.leader._
import com.coralogix.zio.k8s.operator.leader.locks.LeaseLock.VersionedRecord
import zio.clock.Clock
import zio.duration._
import zio.logging.{ log, Logging }
import zio.random.Random
import zio.{ clock, Has, IO, Queue, Ref, Schedule, ZIO, ZManaged }

import java.time.{ DateTimeException, OffsetDateTime }

class LeaseLock(
  lockName: String,
  leases: Leases.Service,
  random: Random.Service,
  leadershipLost: Queue[Unit],
  leaseDuration: Duration,
  renewTimeout: Duration,
  retryPeriod: Duration
) extends LeaderLock {

  override def acquireLock(
    namespace: K8sNamespace,
    pod: Pod
  ): ZManaged[Clock with Logging, leader.LeaderElectionFailure[Nothing], Unit] =
    for {
      store <- Ref.makeManaged[Option[VersionedRecord]](None)
      name  <- pod.getName.mapError(KubernetesError).toManaged_
      impl   = new Impl(store, namespace, name)
      _     <- impl
                 .acquire()
                 .provideSome[Clock with Logging](_ ++ Has(random))
                 .toManaged(_ => impl.release())
      _     <- impl.renew().fork.toManaged_
    } yield ()

  class Impl(
    store: Ref[Option[VersionedRecord]],
    namespace: K8sNamespace,
    identity: String
  ) {
    def acquire(): ZIO[Random with Clock with Logging, LeaderElectionFailure[Nothing], Unit] =
      tryAcquireOrRenew().asSomeError
        .flatMap {
          case false => ZIO.fail(None)
          case true  => ZIO.unit
        }
        .zipLeft(log.info("Leadership acquired"))
        .tapError {
          case None          => ZIO.unit
          case Some(failure) =>
            logLeaderElectionFailure(failure) *>
              log.info("Failed to take leadership, will retry...")
        }
        .retry(Schedule.fixed(retryPeriod).jittered(0.0, 1.2))
        .optional
        .unit

    def renew(): ZIO[Clock with Logging, LeaderElectionFailure[Nothing], Unit] = {
      tryAcquireOrRenew()
        .retry(
          (Schedule.fixed(retryPeriod) >>> Schedule.elapsed)
            .whileOutput(_ < renewTimeout)
            .as(false)
        ) // trying to renew within the renew timeout period
        .repeat(
          Schedule.fixed(retryPeriod).whileInput((success: Boolean) => success)
        ) // keep renewing if it succeeded
    } *> log.info("Leadership lost") *> leadershipLost.offer(()).unit

    def release(): ZIO[Clock with Logging, Nothing, Unit] =
      store.get
        .flatMap {
          case Some(stored) if stored.record.holderIdentity == identity =>
            for {
              now      <- clock.currentDateTime.mapError(DateTimeError)
              newRecord =
                LeaderElectionRecord(
                  holderIdentity = identity,
                  leaseDuration = 1.second,
                  now,
                  now,
                  stored.record.leaderTransitions
                )
              _        <- update(newRecord, stored.resourceVersion)
            } yield ()
          case _                                                        => ZIO.unit
        }
        .catchAll { error =>
          logLeaderElectionFailure(error)
        }

    private def tryAcquireOrRenew()
      : ZIO[Clock with Logging, LeaderElectionFailure[Nothing], Boolean] =
      for {
        latest <- get()
        now    <- clock.currentDateTime.mapError(DateTimeError)
        result <- latest match {
                    case None            =>
                      val record = LeaderElectionRecord(identity, leaseDuration, now, now, 0)
                      for {
                        _               <-
                          log.info(
                            s"Lease record $lockName does not exist, creating and taking leadership"
                          )
                        resourceVersion <- create(record)
                        _               <- store.set(Some(VersionedRecord(record, resourceVersion, now)))
                      } yield true
                    case Some(versioned) =>
                      for {
                        _              <- updateStore(versioned)
                        isLeader        = versioned.record.holderIdentity == identity
                        canBecomeLeader =
                          !(versioned.record.renewTime.plus(leaseDuration).isAfter(now))
                        result         <-
                          if (!isLeader && !canBecomeLeader) {
                            ZIO.succeed(false)
                          } else {
                            val newRecord =
                              LeaderElectionRecord(
                                holderIdentity = identity,
                                leaseDuration = leaseDuration,
                                acquireTime = if (isLeader) versioned.record.acquireTime else now,
                                renewTime = now,
                                leaderTransitions =
                                  if (isLeader) versioned.record.leaderTransitions + 1 else 0
                              )
                            for {
                              newResourceVersion <- update(newRecord, versioned.resourceVersion)
                              updatedRecord       = VersionedRecord(newRecord, newResourceVersion, now)
                              _                  <- updateStore(updatedRecord)
                            } yield true
                          }
                      } yield result
                  }
      } yield result

    private def updateStore(newRecord: VersionedRecord): ZIO[Any, Nothing, Unit] =
      store.update {
        case None                                                    =>
          Some(newRecord)
        case Some(oldRecord) if oldRecord.record != newRecord.record =>
          Some(newRecord)
        case Some(oldRecord)                                         =>
          Some(oldRecord)
      }

    private def get(): ZIO[Clock, LeaderElectionFailure[Nothing], Option[VersionedRecord]] =
      (for {
        lease           <- leases.get(lockName, namespace)
        record          <- toLeaderElectionRecord(lease)
        meta            <- lease.getMetadata
        resourceVersion <- meta.getResourceVersion
        now             <- clock.currentDateTime.orDie
      } yield VersionedRecord(record, resourceVersion, now)).ifFound
        .mapError(KubernetesError.apply)
        .unrefine { case ex: DateTimeException =>
          DateTimeError(ex)
        }

    private def create(record: LeaderElectionRecord): IO[LeaderElectionFailure[Nothing], String] =
      (for {
        lease           <- leases.create(toLease(record, None), namespace)
        leaseMeta       <- lease.getMetadata
        resourceVersion <- leaseMeta.getResourceVersion
      } yield resourceVersion).mapError(KubernetesError.apply)

    private def update(
      record: LeaderElectionRecord,
      resourceVersion: String
    ): IO[LeaderElectionFailure[Nothing], String] =
      (for {
        lease           <- leases.replace(lockName, toLease(record, Some(resourceVersion)), namespace)
        leaseMeta       <- lease.getMetadata
        resourceVersion <- leaseMeta.getResourceVersion
      } yield resourceVersion).mapError(KubernetesError.apply)

    private def toLeaderElectionRecord(lease: Lease): IO[K8sFailure, LeaderElectionRecord] =
      for {
        spec            <- lease.getSpec
        holderIdentity  <- spec.getHolderIdentity
        leaseDuration   <- spec.getLeaseDurationSeconds
        acquireTime     <- spec.getAcquireTime
        renewTime       <- spec.getRenewTime
        leaseTransitions = spec.leaseTransitions.getOrElse(0)
      } yield locks.LeaderElectionRecord(
        holderIdentity,
        leaseDuration.seconds,
        acquireTime.value,
        renewTime.value,
        leaseTransitions
      )

    private def toLease(
      record: LeaderElectionRecord,
      resourceVersion: Optional[String]
    ): Lease =
      Lease(
        metadata = ObjectMeta(
          name = lockName,
          namespace = namespace.value,
          resourceVersion = resourceVersion
        ),
        spec = LeaseSpec(
          holderIdentity = record.holderIdentity,
          leaseDurationSeconds = record.leaseDuration.toSeconds.toInt,
          acquireTime = MicroTime(record.acquireTime),
          renewTime = MicroTime(record.renewTime),
          leaseTransitions = record.leaderTransitions
        )
      )
  }
}

object LeaseLock {
  case class VersionedRecord(
    record: LeaderElectionRecord,
    resourceVersion: String,
    observedAt: OffsetDateTime
  )
}
