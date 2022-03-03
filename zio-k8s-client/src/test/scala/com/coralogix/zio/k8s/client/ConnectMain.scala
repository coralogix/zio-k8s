package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.config.asynchttpclient.k8sDefault
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.v1.pods
import com.coralogix.zio.k8s.client.v1.pods.Pods
import zio._
import zio.stream.ZStream

object ConnectMain extends App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    (for {
      attachProcessState <- ZIO
                              .accessM[Pods](
                                _.get.connectAttach(
                                  "my-release-nginx-866675df97-89pxr",
                                  K8sNamespace.default,
                                  stdout = Some(true),
                                  stderr = Some(true)
                                )
                              )
                              .mapError(error => new Throwable(error.toString))
      _                  <- attachProcessState.stdout
                              .getOrElse(ZStream.empty)
                              .mapError(error => new Throwable(error.toString))
                              .foreachChunk { bytes =>
                                val message = new String(bytes.toArray)
                                console.putStr(message)
                              }
    } yield ())
      .catchAll { error =>
        console.putStrErr(error.getMessage)
      }
      .provideSomeLayer[zio.ZEnv](k8sDefault >>> pods.Pods.live)
      .exitCode

}
