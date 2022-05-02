package com.coralogix.zio.k8s.examples.logs

import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.v1.pods.Pods
import zio.{Console, ZIOAppDefault, _}

import scala.languageFeature.implicitConversions

object LogsExample extends ZIOAppDefault {
  override def run = {
    // Loading config from kubeconfig
    val config = kubeconfig(disableHostnameVerification = true)
      .project(cfg => cfg.dropTrailingDot)

    // K8s configuration and client layers
    val client = config >>> k8sSttpClient
    val cluster = config >>> k8sCluster

    val pods = (client ++ cluster) >>> Pods.live

    // val pods = k8sDefault >>> Pods.live
    val program =
      for {
        pods <- ZIO.service[Pods]
        args <- getArgs
        _    <- args.toList match {
                  case List(podName)                => tailLogs(pods, podName, None)
                  case List(podName, containerName) => tailLogs(pods, podName, Some(containerName))
                  case _                            => Console.printLineError("Usage: <podname> [containername]").ignore
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

    program.provideSome[ZIOAppArgs](pods)
  }

  private def tailLogs(
    pods: Pods,
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
