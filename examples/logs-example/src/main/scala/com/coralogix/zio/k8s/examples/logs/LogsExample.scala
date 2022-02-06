package com.coralogix.zio.k8s.examples.logs

import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.client.v1.pods
import zio._

import zio.logging.{ LogFormat, LogLevel, Logging }

import scala.languageFeature.implicitConversions
import zio.{ Console, Console, ZIOAppDefault }

object LogsExample extends ZIOAppDefault {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    // Loading config from kubeconfig
    val config = kubeconfig(disableHostnameVerification = true)
      .project(cfg => cfg.dropTrailingDot)

    // K8s configuration and client layers
    val client = (System.any ++ config) >>> k8sSttpClient
    val cluster = (config) >>> k8sCluster

    val pods = (client ++ cluster) >>> Pods.live

    // val pods = k8sDefault >>> Pods.live

    val program = args match {
      case List(podName)                => tailLogs(podName, None)
      case List(podName, containerName) => tailLogs(podName, Some(containerName))
      case _                            => Console.printLineError("Usage: <podname> [containername]")
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
        Console.printLine(line).ignore
      }
      .runDrain
}
