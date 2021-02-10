package com.coralogix.zio.k8s.examples.leader

import com.coralogix.zio.k8s.client._
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.kubernetes
import com.coralogix.zio.k8s.client.kubernetes.Kubernetes
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.{ configmaps, pods }
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.operator.Leader
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.config.magnolia.DeriveConfigDescriptor._
import zio.config.magnolia.name
import zio.config.syntax._
import zio.config.typesafe.TypesafeConfig
import zio.logging.{ log, LogFormat, LogLevel, Logging }

import scala.languageFeature.implicitConversions

object LeaderExample extends App {
  case class Config(cluster: K8sClusterConfig, @name("k8s-client") client: K8sClientConfig)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    // Logging
    val logging = Logging.console(
      logLevel = LogLevel.Debug,
      format = LogFormat.ColoredLogFormat()
    ) >>> Logging.withRootLoggerName("leader-example")

    // Loading config from HOCON
    val configDesc = descriptor[Config]
    val config = TypesafeConfig.fromDefaultLoader[Config](configDesc)

    // K8s configuration and client layers
    val client = config.narrow(_.client) >>> k8sSttpClient
    val cluster = (Blocking.any ++ config.narrow(_.cluster)) >>> k8sCluster

    // Pods and ConfigMaps API
    val k8s = (cluster ++ client) >>> kubernetes.live
    //val k8s = (cluster ++ client) >>> (pods.live ++ configmaps.live)

    // Example code
    example()
      //.provideCustomLayer(k8s ++ logging)
      .provideCustomLayer(
        k8s.focus[pods.Service](_.v1.pods) ++
          k8s.focus[configmaps.Service](_.v1.configmaps) ++
          logging
      )
      .exitCode
  }

  private def example(): ZIO[
    Logging with Blocking with system.System with Clock with Pods with ConfigMaps,
    Nothing,
    Option[Nothing]
  ] =
    Leader.leaderForLife("leader-example-lock", None) {
      exampleLeader()
    }

  private def exampleLeader(): ZIO[Logging, Nothing, Nothing] =
    log.info(s"Got leader role") *> ZIO.never
}
