package com.coralogix.zio.k8s.examples.attach

import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.config.httpclient._
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.v1.pods
import com.coralogix.zio.k8s.client.v1.pods.Pods
import zio._
import zio.console.Console
import zio.stream.ZStream

object AttachExample extends App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val pods = k8sDefault >>> Pods.live

    val program = args match {
      case List(podName)                => attach(podName, None)
      case List(podName, containerName) => attach(podName, Some(containerName))
      case _                            => console.putStrLnErr("Usage: <podname> [containername]")
    }

    program
      .provideCustomLayer(pods)
      .exitCode
  }

  private def attach(
    podName: String,
    containerName: Option[String]
  ): ZIO[Pods with Console, K8sFailure, Unit] =
    (for {
      attachProcessState <- pods
                              .connectAttach(
                                name = podName,
                                namespace = K8sNamespace.default,
                                container = containerName,
                                stdout = Some(true),
                                stderr = Some(true)
                              )
      _                  <- attachProcessState.stdout
                              .getOrElse(ZStream.empty)
                              .foreachChunk { bytes =>
                                val message = new String(bytes.toArray)
                                console.putStr(message).ignore
                              }
      status             <- attachProcessState.status.await
      _                  <- console.putStr(status.toString).ignore
    } yield ())
      .mapError(error => error)

}
