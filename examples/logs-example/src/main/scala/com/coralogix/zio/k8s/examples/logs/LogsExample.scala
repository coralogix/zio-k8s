package com.coralogix.zio.k8s.examples.logs

import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.client.v1.pods
import zio._
import zio.blocking.Blocking
import zio.console.Console
import zio.logging.{ LogFormat, LogLevel, Logging }
import zio.system.System

import scala.languageFeature.implicitConversions

object LogsExample extends App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    // Loading config from kubeconfig
    val config = kubeconfig(disableHostnameVerification = true)
      .project(cfg => cfg.dropTrailingDot)

    // K8s configuration and client layers
    val client = (Blocking.any ++ System.any ++ config) >>> k8sSttpClient("test_logger")
    val cluster = (Blocking.any ++ config) >>> k8sCluster

    val pods = (client ++ cluster) >>> Pods.live

    // val pods = k8sDefault >>> Pods.live

    val program = args match {
      case List(podName)                => tailLogs(podName, None)
      case List(podName, containerName) => tailLogs(podName, Some(containerName))
      case _                            => console.putStrLnErr("Usage: <podname> [containername]")
    }

    program
      .provideCustomLayer(pods)
      .exitCode
  }

  private def tailLogs(
    podName: String,
    containerName: Option[String]
  ): ZIO[Pods with Console, K8sFailure, Unit] =
    pods
      .getLog(podName, K8sNamespace.default, container = containerName, follow = Some(true))
      .tap { line =>
        console.putStrLn(line).ignore
      }
      .runDrain
}
