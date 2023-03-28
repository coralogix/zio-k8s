package com.coralogix.zio.k8s.client.config

import com.coralogix.zio.k8s.client.model.K8sCluster
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import sttp.client3.httpclient.zio._
import sttp.client3.logging.LoggingBackend
import sttp.client3.logging.slf4j.{ Slf4jLogger, Slf4jLoggingBackend }
import zio._

import java.net.http.HttpClient

/** HTTP client implementation based on the httpclient-zio backend
  */
package object httpclient {

  /** An STTP backend layer configured with the proper SSL context based on the provided
    * [[K8sClusterConfig]] using the httpclient-backend-zio backend.
    */
  def k8sSttpClient(
    loggerName: String = "sttp.client3.logging.slf4j.Slf4jLoggingBackend"
  ): ZLayer[K8sClusterConfig, Throwable, SttpBackend[Task, ZioStreams with WebSockets]] =
    ZLayer.scoped {
      for {
        config                      <- ZIO.service[K8sClusterConfig]
        disableHostnameVerification <- ZIO.succeed(getHostnameVerificationDisabled(config))
        _                           <- ZIO
                                         .attempt {
                                           java.lang.System.setProperty(
                                             "jdk.internal.httpclient.disableHostnameVerification",
                                             "true"
                                           )
                                         }
                                         .when(disableHostnameVerification)

        sslContext <- SSL(config.client.serverCertificate, config.authentication)
        client     <- ZIO.scoped(
                        ZIO
                          .acquireRelease(
                            ZIO.attempt(
                              HttpClientZioBackend.usingClient(
                                HttpClient
                                  .newBuilder()
                                  .followRedirects(HttpClient.Redirect.NORMAL)
                                  .sslContext(sslContext)
                                  .build()
                              )
                            )
                          )(_.close().ignore)
                          .map { backend =>
                            LoggingBackend(
                              backend,
                              new Slf4jLogger(loggerName, backend.responseMonad),
                              logRequestBody = config.client.debug,
                              logResponseBody = config.client.debug
                            )
                          }
                      )
      } yield client
    }

  /** Layer producing a [[K8sCluster]] and an STTP backend module that can be directly used to
    * initialize specific Kubernetes client modules, using the [[defaultConfigChain]].
    */
  val k8sDefault
    : ZLayer[Any, Throwable, K8sCluster with SttpBackend[Task, ZioStreams with WebSockets]] =
    defaultConfigChain >>> (k8sCluster ++ k8sSttpClient())

  def getHostnameVerificationDisabled(config: K8sClusterConfig) =
    config.client.serverCertificate match {
      case K8sServerCertificate.Insecure                               => true
      case K8sServerCertificate.Secure(_, disableHostnameVerification) =>
        disableHostnameVerification
    }
}
