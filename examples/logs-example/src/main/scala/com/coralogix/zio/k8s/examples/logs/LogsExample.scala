package com.coralogix.zio.k8s.examples.logs

import com.coralogix.zio.k8s.client.{ model, K8sFailure }
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.v1.pods
import com.coralogix.zio.k8s.client.v1.pods.Pods
import sttp.client3.httpclient.zio.SttpClient
import zio.{ Console, ZIOAppDefault, _ }

import scala.languageFeature.implicitConversions

object LogsExample extends ZIOAppDefault {
  override def run: ZIO[ZEnv with ZIOAppArgs, Any, Any] = {
    // Loading config from kubeconfig
    val config = kubeconfig(disableHostnameVerification = true)
      .project(cfg => cfg.dropTrailingDot)

    // K8s configuration and client layers
    val client = (System.any ++ config) >>> k8sSttpClient
    val cluster = (config) >>> k8sCluster

    val pods = (client ++ cluster) >>> Pods.live

    // val pods = k8sDefault >>> Pods.live
    val program = for {
      args <- ZIOAppArgs.getArgs
      _    <- args.toList match {
                case List(podName)                => tailLogs(podName, None)
                case List(podName, containerName) => tailLogs(podName, Some(containerName))
                case _                            => Console.printLineError("Usage: <podname> [containername]")
              }
    } yield ()

//    val program = ZIO
//      .environmentWithZIO[ZIOAppArgs] {
//        _.get.getArgs.toList match {
//          case List(podName)                => tailLogs(podName, None)
//          case List(podName, containerName) => tailLogs(podName, Some(containerName))
//          case _                            => Console.printLineError("Usage: <podname> [containername]")
//        }
//      }

    program
      .provideSome(pods)
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
