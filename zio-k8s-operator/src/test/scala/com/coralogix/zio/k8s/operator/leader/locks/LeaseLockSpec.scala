package com.coralogix.zio.k8s.operator.leader.locks

import com.coralogix.zio.k8s.client.{
  HttpFailure,
  K8sFailure,
  K8sRequestInfo,
  NotFound,
  RequestFailure,
  Resource,
  ResourceDelete,
  ResourceDeleteAll
}
import com.coralogix.zio.k8s.client.coordination.v1.leases
import com.coralogix.zio.k8s.client.coordination.v1.leases.Leases
import com.coralogix.zio.k8s.client.model.{
  FieldSelector,
  K8sNamespace,
  K8sResourceType,
  LabelSelector,
  ListResourceVersion,
  PropagationPolicy,
  TypedWatchEvent
}
import com.coralogix.zio.k8s.model.coordination.v1.{ Lease, LeaseSpec }
import com.coralogix.zio.k8s.model.core.v1.Pod
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, MicroTime, ObjectMeta, Status }
import com.coralogix.zio.k8s.operator.contextinfo.ContextInfo
import com.coralogix.zio.k8s.operator.leader
import com.coralogix.zio.k8s.operator.leader.{ lease, LeaderElection }
import zio.{ clock, console, stream, Fiber, IO, RIO, Ref, UIO, ULayer, ZIO, ZLayer }
import zio.Clock

import zio.logging.Logging
import zio.stream.ZStream
import zio.test.Assertion.equalTo
import zio.test.environment.TestEnvironment
import zio.test._
import zio.test.Assertion._
import zio.{ Clock, Console, Random, _ }
import zio.test.{ TestClock, ZIOSpecDefault }

object LeaseLockSpec extends ZIOSpecDefault {

  private def leaderElection(
    name: String
  ): ZLayer[Clock with Console with Random with Leases, Nothing, LeaderElection with Logging] =
    (Random.any ++
      Console.any ++
      Clock.any ++
      ContextInfo.test(
        Pod(ObjectMeta(name = name)),
        K8sNamespace.default
      ) ++ Leases.any) >>>
      (LeaderElection.leaseLock(
        "test-lock",
        leaseDuration = 15.seconds,
        renewTimeout = 10.seconds,
        retryPeriod = 2.seconds
      ) ++ Logging.console())

  trait TestLeases {
    def enableFailures: UIO[Unit]
    def disableFailures: UIO[Unit]
  }

  def enableFailures: RIO[TestLeases, Unit] = ZIO.service[TestLeases].flatMap(_.enableFailures)
  def disableFailures: RIO[TestLeases, Unit] =
    ZIO.service[TestLeases].flatMap(_.disableFailures)

  private def failingLeases: ULayer[Leases with TestLeases] =
    Leases.test >>> ZLayer
      .fromServiceManyM[Leases.Service, Any, Nothing, Leases with TestLeases] { testImpl =>
        Ref.make(true).map { failSwitch =>
          val testLeases = new TestLeases {
            override def enableFailures: UIO[Unit] =
              failSwitch.set(true)

            override def disableFailures: UIO[Unit] =
              failSwitch.set(false)
          }
          val leases = new Leases.Live(
            new Resource[Lease] with ResourceDelete[Lease, Status] with ResourceDeleteAll[Lease] {
              override def getAll(
                namespace: Option[K8sNamespace],
                chunkSize: Int,
                fieldSelector: Option[FieldSelector],
                labelSelector: Option[LabelSelector],
                resourceVersion: ListResourceVersion
              ): stream.Stream[K8sFailure, Lease] =
                ZStream.unwrap {
                  ifZIO(failSwitch.get)(
                    ZIO.succeed(
                      ZStream.fail(
                        RequestFailure(
                          K8sRequestInfo(K8sResourceType("kind", "group", "version"), "getAll"),
                          new RuntimeException("test failure")
                        )
                      )
                    ),
                    ZIO.succeed(
                      testImpl.getAll(
                        namespace,
                        chunkSize,
                        fieldSelector,
                        labelSelector,
                        resourceVersion
                      )
                    )
                  )
                }

              override def watch(
                namespace: Option[K8sNamespace],
                resourceVersion: Option[String],
                fieldSelector: Option[FieldSelector],
                labelSelector: Option[LabelSelector]
              ): stream.Stream[K8sFailure, TypedWatchEvent[Lease]] =
                ZStream.unwrap {
                  ifZIO(failSwitch.get)(
                    ZIO.succeed(
                      ZStream.fail(
                        RequestFailure(
                          K8sRequestInfo(K8sResourceType("kind", "group", "version"), "watch"),
                          new RuntimeException("test failure")
                        )
                      )
                    ),
                    ZIO.succeed(
                      testImpl.watch(namespace, resourceVersion, fieldSelector, labelSelector)
                    )
                  )
                }

              override def get(name: String, namespace: Option[K8sNamespace])
                : IO[K8sFailure, Lease] =
                ifZIO(failSwitch.get)(
                  ZIO.fail(
                    RequestFailure(
                      K8sRequestInfo(K8sResourceType("kind", "group", "version"), "get"),
                      new RuntimeException("test failure")
                    )
                  ),
                  testImpl.get(name, namespace.get)
                )

              override def create(
                newResource: Lease,
                namespace: Option[K8sNamespace],
                dryRun: Boolean
              ): IO[K8sFailure, Lease] =
                ifZIO(failSwitch.get)(
                  ZIO.fail(
                    RequestFailure(
                      K8sRequestInfo(K8sResourceType("kind", "group", "version"), "create"),
                      new RuntimeException("test failure")
                    )
                  ),
                  testImpl.create(newResource, namespace.get, dryRun)
                )

              override def replace(
                name: String,
                updatedResource: Lease,
                namespace: Option[K8sNamespace],
                dryRun: Boolean
              ): IO[K8sFailure, Lease] =
                ifZIO(failSwitch.get)(
                  ZIO.fail(
                    RequestFailure(
                      K8sRequestInfo(K8sResourceType("kind", "group", "version"), "replace"),
                      new RuntimeException("test failure")
                    )
                  ),
                  testImpl.replace(name, updatedResource, namespace.get, dryRun)
                )

              override def delete(
                name: String,
                deleteOptions: DeleteOptions,
                namespace: Option[K8sNamespace],
                dryRun: Boolean,
                gracePeriod: Option[Duration],
                propagationPolicy: Option[PropagationPolicy]
              ): IO[K8sFailure, Status] =
                ifZIO(failSwitch.get)(
                  ZIO.fail(
                    RequestFailure(
                      K8sRequestInfo(K8sResourceType("kind", "group", "version"), "delete"),
                      new RuntimeException("test failure")
                    )
                  ),
                  testImpl.delete(
                    name,
                    deleteOptions,
                    namespace.get,
                    dryRun,
                    gracePeriod,
                    propagationPolicy
                  )
                )

              override def deleteAll(
                deleteOptions: DeleteOptions,
                namespace: Option[K8sNamespace],
                dryRun: Boolean,
                gracePeriod: Option[Duration],
                propagationPolicy: Option[PropagationPolicy],
                fieldSelector: Option[FieldSelector],
                labelSelector: Option[LabelSelector]
              ): IO[K8sFailure, Status] =
                ifZIO(failSwitch.get)(
                  ZIO.fail(
                    RequestFailure(
                      K8sRequestInfo(K8sResourceType("kind", "group", "version"), "deleteAll"),
                      new RuntimeException("test failure")
                    )
                  ),
                  testImpl.deleteAll(
                    deleteOptions,
                    namespace.get,
                    dryRun,
                    gracePeriod,
                    propagationPolicy,
                    fieldSelector,
                    labelSelector
                  )
                )
            }
          )
          Has[Leases.Service](leases) ++ Has(testLeases)
        }
      }

  private def singleton(
    counter: Ref[Int],
    winner: Ref[String],
    name: String
  ): ZIO[Any, Nothing, Nothing] =
    (counter.update(_ + 1) *> winner.set(name) *> ZIO.never).ensuring(counter.update(_ - 1))

  override def spec: ZSpec[TestEnvironment, Any] =
    suite("Lease based leader election")(
      test("simultaneous startup, only one leads") {
        for {
          ref    <- Ref.make(0)
          winner <- Ref.make("")

          f1 <- leader
                  .runAsLeader(singleton(ref, winner, "pod1"))
                  .fork
                  .provideSomeLayer(leaderElection("pod1"))
          f2 <- leader
                  .runAsLeader(singleton(ref, winner, "pod2"))
                  .fork
                  .provideSomeLayer(leaderElection("pod2"))

          _  <- TestClock.adjust(5.seconds)
          c1 <- ref.get
          _  <- TestClock.adjust(25.seconds)
          c2 <- ref.get

          _ <- f1.interrupt
          _ <- f2.interrupt
        } yield assert(c1)(equalTo(1)) && assert(c2)(equalTo(1))
      }.provideCustomLayer(Leases.test),
      test("non-leader takes over if leader is interrupted") {
        for {
          ref    <- Ref.make(0)
          winner <- Ref.make("")

          f1 <- leader
                  .runAsLeader(singleton(ref, winner, "pod1"))
                  .fork
                  .provideSomeLayer(leaderElection("pod1"))
          _  <- TestClock.adjust(5.seconds)
          w1 <- winner.get

          f2 <- leader
                  .runAsLeader(singleton(ref, winner, "pod2"))
                  .fork
                  .provideSomeLayer(leaderElection("pod2"))
          _  <- TestClock.adjust(5.seconds)
          w2 <- winner.get

          _ <- f1.interrupt

          _  <- TestClock.adjust(30.seconds)
          w3 <- winner.get
          _  <- f2.interrupt
        } yield assert(w1)(equalTo("pod1")) && assert(w2)(equalTo("pod1")) && assert(w3)(
          equalTo("pod2")
        )
      }.provideCustomLayer(Leases.test),
      test("leader gets interrupted if lease get stolen") {
        for {
          ref    <- Ref.make(0)
          winner <- Ref.make("")

          f1 <- leader
                  .runAsLeader(singleton(ref, winner, "pod1"))
                  .fork
                  .provideSomeLayer(leaderElection("pod1"))
          _  <- TestClock.adjust(5.seconds)
          w1 <- winner.get
          c1 <- ref.get

          // Replacing the lease
          _   <- leases.delete("test-lock", DeleteOptions(), K8sNamespace.default)
          now <- Clock.currentDateTime
          _   <- leases.create(
                   Lease(
                     ObjectMeta(name = "test-lock"),
                     LeaseSpec(
                       acquireTime = MicroTime(now),
                       holderIdentity = "thief",
                       leaseDurationSeconds = 180,
                       leaseTransitions = 0,
                       renewTime = MicroTime(now)
                     )
                   ),
                   K8sNamespace.default
                 )

          _      <- TestClock.adjust(30.seconds)
          c2     <- ref.get
          status <- f1.status
        } yield assert(w1)(equalTo("pod1")) &&
          assert(c1)(equalTo(1)) &&
          assert(c2)(equalTo(0)) &&
          assert(status)(equalTo(Fiber.Status.Done))
      }.provideCustomLayer(Leases.test),
      test("never become leader with no K8s access") {
        for {
          ref    <- Ref.make(0)
          winner <- Ref.make("")

          f1 <- leader
                  .runAsLeader(singleton(ref, winner, "pod1"))
                  .fork
                  .provideSomeLayer(leaderElection("pod1"))

          _ <- TestClock.adjust(60.seconds)
          w <- winner.get

          _ <- f1.interrupt
        } yield assert(w)(isEmptyString)
      }.provideCustomLayer(failingLeases),
      test("with clock skew leadership can be stolen but other gets cancelled") {
        TestClock.default.build.use { otherClock =>
          for {
            ref    <- Ref.make(0)
            winner <- Ref.make("")

            _ <- otherClock.get[TestClock].adjust(20.seconds)

            f1 <- leader
                    .runAsLeader(singleton(ref, winner, "pod1"))
                    .fork
                    .provideSomeLayer(leaderElection("pod1"))

            _  <- leases.get("test-lock", K8sNamespace.default).retryWhile(_ == NotFound)
            w0 <- winner.get.repeatUntil(_ == "pod1")

            f2 <- leader
                    .runAsLeader(singleton(ref, winner, "pod2"))
                    .fork
                    .provideSomeLayer(leaderElection("pod2"))
                    .provideSome[Console with Random with Leases](_ ++ otherClock)

            _  <- TestClock.adjust(5.seconds)
            _  <- otherClock.get[TestClock].adjust(5.seconds)
            _  <- f1.join
            w1 <- winner.get

            _ <- f1.interrupt
            _ <- f2.interrupt
          } yield assert(w0)(equalTo("pod1")) &&
            assert(w1)(equalTo("pod2"))
        }
      }.provideCustomLayer(Leases.test),
      test("becomes leader then fails to renew and gets aborted") {
        for {
          _      <- disableFailures
          ref    <- Ref.make(0)
          winner <- Ref.make("")

          f1 <- leader
                  .runAsLeader(singleton(ref, winner, "pod1"))
                  .fork
                  .provideSomeLayer(leaderElection("pod1"))

          _ <- winner.get.repeatUntil(_ == "pod1")
          _ <- enableFailures
          _ <- TestClock.adjust(60.seconds)
          _ <- f1.join
          w <- winner.get
        } yield assert(w)(equalTo("pod1"))
      }.provideCustomLayer(failingLeases)
    )
}
