package com.coralogix.zio.k8s.examples.shell

import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.config.httpclient._
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.v1.pods
import com.coralogix.zio.k8s.client.v1.pods.Pods
import zio._
import zio.console.Console
import zio.stream.ZStream

import scala.languageFeature.implicitConversions

object ShellExample extends App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val pods = k8sDefault >>> Pods.live

    val program = args match {
      case List(podName, command)                => exec(podName, command, None)
      case List(podName, command, containerName) => exec(podName, command, Some(containerName))
      case _                                     => console.putStrLnErr("Usage: <podname> <command> [containername]")
    }

    program
      .provideCustomLayer(pods)
      .exitCode
  }

  private def exec(
    podName: String,
    command: String,
    containerName: Option[String]
  ): ZIO[Pods with Console, K8sFailure, Unit] =
    (for {
      attachProcessState <- pods
                              .connectExec(
                                name = podName,
                                namespace = K8sNamespace.default,
                                container = containerName,
                                command = Some(command),
                                stdin = Some(true),
                                stdout = Some(true),
                                tty = Some(true)
                              )
      stdoutProcess       = attachProcessState.stdout
                              .getOrElse(ZStream.empty)
                              .foreachChunk { bytes =>
                                val message = new String(bytes.toArray)
                                console.putStr(message)
                              }
                              .ignore
      stdinProcess        = ZStream
                              .repeatEffect(
                                console.getStrLn.map(line => Chunk.fromArray((line + "\n").getBytes))
                              )
                              .interruptWhen(attachProcessState.status.await.ignore)
                              .run(attachProcessState.stdin.get)
                              .ignore
      _                  <- stdoutProcess <&> stdoutProcess
    } yield ())
      .mapError(error => error)

}
