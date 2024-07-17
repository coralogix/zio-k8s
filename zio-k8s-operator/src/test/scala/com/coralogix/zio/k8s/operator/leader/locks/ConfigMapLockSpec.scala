package com.coralogix.zio.k8s.operator.leader.locks

import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.model.core.v1.Pod
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import com.coralogix.zio.k8s.operator.contextinfo.ContextInfo
import com.coralogix.zio.k8s.operator.leader
import com.coralogix.zio.k8s.operator.leader.LeaderElection
import zio.ZIO.logInfo
import zio.test.TestAspect.timeout
import zio.test.{ assertTrue, Spec, TestEnvironment, ZIOSpecDefault }
import zio.{ durationInt, Promise, Schedule, ZIO, ZLayer }

object ConfigMapLockSpec extends ZIOSpecDefault {
  private val sharedLayers = ZLayer.make[Pods with ConfigMaps](
    // The function here is only for deletes and should never be called.
    Pods.test(() => ???),
    ConfigMaps.test
  )

  private def makePod(name: String) =
    Pod(metadata = Some(ObjectMeta(name = name, uid = Some(name))))

  // Schedule.stop is used so that if acquisition fails, it does not retry.
  private val leaderElectionLayer = LeaderElection.configMapLock("abc", retryPolicy = Schedule.stop)

  override def spec: Spec[TestEnvironment, Any] =
    suite("ConfigMap based leader election")(
      singleLeaderTest,
      reentranceTest
    ).provide(sharedLayers) @@ timeout(5.seconds)

  // A ZIO that hogs the lock and never completes. The promise is there to let the caller know that it has started.
  def lockHogger(promise: Promise[Nothing, Unit]): ZIO[Any, Nothing, Unit] = for {
    _ <- promise.succeed(())
    _ <- logInfo("Running locked code")
    _ <- ZIO.never
  } yield ()

  val singleLeaderTest: Spec[Pods with ConfigMaps, Any] =
    test("second fiber should fail to acquire lock") {
      // We create two ContextInfos so that the two ZIOs trying to get locks can pretend to be different Pods competing
      // for the same lock.
      val ciLayer1 = ContextInfo.test(makePod("pod1"), K8sNamespace.default)
      val ciLayer2 = ContextInfo.test(makePod("pod2"), K8sNamespace.default)
      for {
        promise <- Promise.make[Nothing, Unit]
        fiber   <- leader
                     .runAsLeader(lockHogger(promise))
                     .provideSome[ConfigMaps with Pods](ciLayer1, leaderElectionLayer)
                     .fork
        // Ensure that fiber has acquired the lock.
        _       <- promise.await
        // This will return None since it cannot get the lock.
        result  <- leader
                     .runAsLeader(ZIO.unit)
                     .provideSome[ConfigMaps with Pods](ciLayer2, leaderElectionLayer)
        _       <- fiber.interrupt
      } yield assertTrue(result.isEmpty)
    }

  // The lock should be reentrant.
  val reentranceTest: Spec[Pods with ConfigMaps, Any] =
    test("a pod that already has the lock should be able to reuse it") {
      val ciLayer = ContextInfo.test(makePod("pod1"), K8sNamespace.default)
      for {
        promise <- Promise.make[Nothing, Unit]
        fiber   <- leader
                     .runAsLeader(lockHogger(promise))
                     .provideSome[ConfigMaps with Pods](ciLayer, leaderElectionLayer)
                     .fork
        // Ensure that fiber1 has acquired the lock.
        _       <- promise.await
        // Now reusing the ContextInfo, so it's the same Pod. It already has the lock, so should run successfully.
        result  <- leader
                     .runAsLeader(ZIO.unit)
                     .provideSome[ConfigMaps with Pods](ciLayer, leaderElectionLayer)
        _       <- fiber.interrupt
      } yield assertTrue(result.nonEmpty)
    }
}
