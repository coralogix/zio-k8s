package com.coralogix.zio.k8s.client.config

import com.coralogix.zio.k8s.client.model.K8sCluster
import io.netty.handler.ssl.{ ClientAuth, IdentityCipherSuiteFilter, JdkSslContext }
import org.asynchttpclient.Dsl
import sttp.client3.asynchttpclient.zio._
import sttp.client3.httpclient.zio.SttpClient
import sttp.client3.logging.slf4j.Slf4jLoggingBackend
import zio._

/** HTTP client implementation based on the async-http-client-zio backend
  */
package object asynchttpclient {

  /** An [[SttpClient]] layer configured with the proper SSL context based on the provided
    * [[K8sClusterConfig]] using the async-http-client-backend-zio backend.
    */
  val k8sSttpClient: ZLayer[K8sClusterConfig with System, Throwable, SttpClient] = ZLayer.scoped {
    for {
      config                      <- ZIO.service[K8sClusterConfig]
      runtime                     <- ZIO.runtime[Any]
      sslContext                  <- SSL(config.client.serverCertificate, config.authentication)
      disableHostnameVerification <- ZIO.succeed(getHostnameVerificationDisabled(config))
      client                      <-
        ZIO
          .acquireRelease(
            ZIO.attempt {
              AsyncHttpClientZioBackend.usingClient(
                runtime,
                Dsl.asyncHttpClient(
                  Dsl
                    .config()
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
              )
            }
          )(_.close().ignore)
          .map { backend =>
            Slf4jLoggingBackend(
              backend,
              logRequestBody = config.client.debug,
              logResponseBody = config.client.debug
            )
          }
    } yield client
  }

  def getHostnameVerificationDisabled(config: K8sClusterConfig) =
    config.client.serverCertificate match {
      case K8sServerCertificate.Insecure                               => true
      case K8sServerCertificate.Secure(_, disableHostnameVerification) =>
        disableHostnameVerification
    }

  /** Layer producing a [[K8sCluster]] and an [[SttpClient]] module that can be directly used to
    * initialize specific Kubernetes client modules, using the [[defaultConfigChain]].
    */
  val k8sDefault: ZLayer[System, Throwable, K8sCluster with SttpClient] =
    defaultConfigChain >>> (k8sCluster ++ k8sSttpClient)
}
