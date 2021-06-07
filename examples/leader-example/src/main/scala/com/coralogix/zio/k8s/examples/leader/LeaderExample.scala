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
import zio.blocking.Blocking
import zio.clock.Clock
import zio.logging.{ log, LogFormat, LogLevel, Logging }
import zio.magic._

import scala.languageFeature.implicitConversions

object LeaderExample extends App {
  case class Config(k8s: K8sClusterConfig)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    // Logging
    val logging = Logging.console(
      logLevel = LogLevel.Debug,
      format = LogFormat.ColoredLogFormat()
    ) >>> Logging.withRootLoggerName("leader-example")

    // Pods and ConfigMaps API
    val k8s =
      k8sDefault >>> (Pods.live ++ Leases.live ++ CustomResourceDefinitions.live ++ ContextInfo.live)
    val leaderElection = k8s >>> LeaderElection.leaseLock("leader-example-lock")

    // Example code
    val program =
      Registration.registerIfMissing[LeaderLockResource](
        LeaderLockResource.customResourceDefinition
      ) *>
        example()

    program
      .provideCustomLayer(logging ++ k8s ++ leaderElection)
      .exitCode
  }

  private def example(): ZIO[
    Logging with Blocking with system.System with Clock with LeaderElection,
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
