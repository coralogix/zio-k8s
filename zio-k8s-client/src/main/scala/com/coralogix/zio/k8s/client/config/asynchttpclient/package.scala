package com.coralogix.zio.k8s.client.config

import com.coralogix.zio.k8s.client.config.backend.K8sBackend
import com.coralogix.zio.k8s.client.model.K8sCluster
import io.netty.handler.ssl.{ ClientAuth, IdentityCipherSuiteFilter, JdkSslContext }
import org.asynchttpclient.Dsl
import sttp.client3.asynchttpclient.zio._
import sttp.client3.logging.LoggingBackend
import sttp.client3.logging.slf4j.Slf4jLogger
import zio._

/** HTTP client implementation based on the async-http-client-zio backend
  */
package object asynchttpclient {

  /** An STTP backend layer configured with the proper SSL context based on the provided
    * [[K8sClusterConfig]] using the async-http-client-backend-zio backend.
    */
  def k8sSttpClient(
    loggerName: String = "sttp.client3.logging.slf4j.Slf4jLoggingBackend"
  ): ZLayer[K8sClusterConfig, Throwable, K8sBackend] =
    ZLayer.scoped {
      for {
        config                      <- ZIO.service[K8sClusterConfig]
        runtime                     <- ZIO.runtime[Any]
        sslContext                  <- SSL(config.client.serverCertificate, config.authentication)
        disableHostnameVerification <- ZIO.succeed(getHostnameVerificationDisabled(config))
        sttpBackend                 <-
          AsyncHttpClientZioBackend.scopedUsingConfigBuilder(configBuilder =>
            configBuilder
              .setFollowRedirect(true)
              .setDisableHttpsEndpointIdentificationAlgorithm(disableHostnameVerification)
              .setSslContext(
                new JdkSslContext(
                  sslContext,
                  true,
                  null,
                  IdentityCipherSuiteFilter.INSTANCE,
                  null,
                  ClientAuth.NONE,
                  null,
                  false
                )
              )
          )
      } yield K8sBackend(
        LoggingBackend(
          sttpBackend,
          new Slf4jLogger(loggerName, sttpBackend.responseMonad),
          logRequestBody = config.client.debug,
          logResponseBody = config.client.debug
        )
      )
    }

  def getHostnameVerificationDisabled(config: K8sClusterConfig) =
    config.client.serverCertificate match {
      case K8sServerCertificate.Insecure                               => true
      case K8sServerCertificate.Secure(_, disableHostnameVerification) =>
        disableHostnameVerification
    }

  /** Layer producing a [[K8sCluster]] and an sttp backend module that can be directly used to
    * initialize specific Kubernetes client modules, using the [[defaultConfigChain]].
    */
  val k8sDefault: ZLayer[Any, Throwable, K8sCluster with K8sBackend] =
    defaultConfigChain >>> (k8sCluster ++ k8sSttpClient())
}
