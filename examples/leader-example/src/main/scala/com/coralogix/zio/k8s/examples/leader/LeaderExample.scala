package com.coralogix.zio.k8s.examples.leader

import com.coralogix.zio.k8s.client.apiextensions.v1.customresourcedefinitions.CustomResourceDefinitions
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import com.coralogix.zio.k8s.client.coordination.v1.leases.Leases
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.operator.contextinfo.ContextInfo
import com.coralogix.zio.k8s.operator.{ leader, Registration }
import com.coralogix.zio.k8s.operator.leader.LeaderElection
import com.coralogix.zio.k8s.operator.leader.locks.LeaderLockResource
import com.coralogix.zio.k8s.operator.leader.locks.leaderlockresources.LeaderLockResources
import zio._

import zio.Clock
import zio.logging.{ log, LogFormat, LogLevel, Logging }

import scala.languageFeature.implicitConversions
import zio.{ System, ZIOAppDefault }

object LeaderExample extends ZIOAppDefault {
  case class Config(k8s: K8sClusterConfig)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    // Logging
    val logging = Logging.console(
      logLevel = LogLevel.Debug,
      format = LogFormat.ColoredLogFormat()
    ) >>> Logging.withRootLoggerName("leader-example")

    // Pods and ConfigMaps API
    val pods = k8sDefault >>> Pods.live
    val leases = k8sDefault >>> Leases.live
    val crds = k8sDefault >>> CustomResourceDefinitions.live
    val contextInfo =
      (System.any ++ pods) >>> ContextInfo.live.mapError(f => FiberFailure(Cause.fail(f)))
    val leaderElection =
      (Random.any ++ leases ++ contextInfo) >>> LeaderElection.leaseLock("leader-example-lock")

    // Example code
    val program =
      Registration.registerIfMissing[LeaderLockResource](
        LeaderLockResource.customResourceDefinition
      ) *>
        example()

    program
      .provideCustomLayer(logging ++ crds ++ leaderElection)
      .exitCode
  }

  private def example(): ZIO[
    Logging with Any with System with Clock with LeaderElection,
    Nothing,
    Option[Nothing]
  ] =
    leader
      .runAsLeader {
        exampleLeader()
      }
      .repeatWhile(_.isEmpty)

  private def exampleLeader(): ZIO[Logging, Nothing, Nothing] =
    log.info(s"Got leader role") *> ZIO.never
}
