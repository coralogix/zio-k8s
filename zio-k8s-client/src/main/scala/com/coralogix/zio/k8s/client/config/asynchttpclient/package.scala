package com.coralogix.zio.k8s.client.config

import com.coralogix.zio.k8s.client.model.K8sCluster
import io.netty.handler.ssl.{ ClientAuth, IdentityCipherSuiteFilter, JdkSslContext }
import org.asynchttpclient.Dsl
import sttp.client3.asynchttpclient.zio._
import sttp.client3.httpclient.zio.SttpClient
import sttp.client3.logging.slf4j.Slf4jLoggingBackend
import zio.{ System, ZLayer, ZManaged }

/** HTTP client implementation based on the async-http-client-zio backend
  */
package object asynchttpclient {

  /** An [[SttpClient]] layer configured with the proper SSL context based on the provided
    * [[K8sClusterConfig]] using the async-http-client-backend-zio backend.
    */
  val k8sSttpClient: ZLayer[K8sClusterConfig with System with Any, Throwable, SttpClient] = {
    (config: K8sClusterConfig) =>
      (for {
        runtime                    <- ZManaged.runtime[Any]
        sslContext                 <- SSL(config.client.serverCertificate, config.authentication).toManaged
        disableHostnameVerification = config.client.serverCertificate match {
                                        case K8sServerCertificate.Insecure => true
                                        case K8sServerCertificate.Secure(
                                              _,
                                              disableHostnameVerification
                                            ) =>
                                          disableHostnameVerification
                                      }
        client                     <-
          ZManaged
            .acquireReleaseAttemptWith(
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
            )(_.close().ignore)
            .map { backend =>
              Slf4jLoggingBackend(
                backend,
                logRequestBody = config.client.debug,
                logResponseBody = config.client.debug
              )
            }
      } yield client).toLayer[SttpClient]
  }.toLayer.flatten

  /** Layer producing a [[K8sCluster]] and an [[SttpClient]] module that can be directly used to
    * initialize specific Kubernetes client modules, using the [[defaultConfigChain]].
    */
  val k8sDefault: ZLayer[Any with System, Throwable, K8sCluster with SttpClient] =
    (System.any) >+> defaultConfigChain >>> (k8sCluster ++ k8sSttpClient)
}
