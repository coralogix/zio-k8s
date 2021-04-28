package com.coralogix.zio.k8s.examples.leader

import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.operator.contextinfo.ContextInfo
import com.coralogix.zio.k8s.operator.leader
import com.coralogix.zio.k8s.operator.leader.LeaderElection
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

    // Loading config from HOCON
//    val configDesc = descriptor[Config]
//    val config = TypesafeConfig.fromDefaultLoader[Config](configDesc)

    // K8s configuration and client layers
//    val client = (System.any ++ Blocking.any ++ config.project(_.k8s)) >>> k8sSttpClient
//    val cluster = (Blocking.any ++ config.project(_.k8s)) >>> k8sCluster

    // Pods and ConfigMaps API
//    val k8s = (cluster ++ client) >>> Kubernetes.live
//    val k8s = k8sDefault >>> Kubernetes.live
//    val k8s = k8sDefault >>> (Pods.live ++ ConfigMaps.live)
//    val leaderElection = k8s >>> LeaderElection.configMapLock("leader-example-lock")

    // Example code
    example()
      //.provideCustomLayer(k8s ++ logging)
      .injectCustom(
        logging,
        k8sDefault,
        ContextInfo.live,
        Pods.live,
//        ConfigMaps.live,
//        LeaderElection.configMapLock("leader-example-lock"),
        LeaderLockResources.live,
        LeaderElection.customLeaderLock("leader-example-lock", deleteLockOnRelease = false)
      )
      .exitCode
  }

  private def example(): ZIO[
    Logging with Blocking with system.System with Clock with LeaderElection,
    Nothing,
    Option[Nothing]
  ] =
    leader.runAsLeader {
      exampleLeader()
    }

  private def exampleLeader(): ZIO[Logging, Nothing, Nothing] =
    log.info(s"Got leader role") *> ZIO.never
}
