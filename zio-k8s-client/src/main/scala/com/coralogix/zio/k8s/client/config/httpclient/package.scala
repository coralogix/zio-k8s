package com.coralogix.zio.k8s.client.config

import com.coralogix.zio.k8s.client.model.K8sCluster
import sttp.client3.httpclient.zio._
import sttp.client3.logging.slf4j.Slf4jLoggingBackend
import zio.blocking.Blocking
import zio.system.System
import zio.{ Has, ZIO, ZLayer, ZManaged }

import java.net.http.HttpClient

/** HTTP client implementation based on the httpclient-zio backend
  */
package object httpclient {

  /** An [[SttpClient]] layer configured with the proper SSL context based
    * on the provided [[K8sClusterConfig]] using the httpclient-backend-zio backend.
    */
  val k8sSttpClient
    : ZLayer[Has[K8sClusterConfig] with System with Blocking, Throwable, SttpClient] =
    ZLayer.fromServiceManaged { config: K8sClusterConfig =>
      val disableHostnameVerification = config.client.serverCertificate match {
        case K8sServerCertificate.Insecure                               => true
        case K8sServerCertificate.Secure(_, disableHostnameVerification) =>
          disableHostnameVerification
      }
      for {
        _          <- ZIO
                        .effect {
                          java.lang.System.setProperty(
                            "jdk.internal.httpclient.disableHostnameVerification",
                            "true"
                          )
                        }
                        .when(disableHostnameVerification)
                        .toManaged_
        sslContext <- SSL(config.client.serverCertificate, config.authentication).toManaged_
        client     <- ZManaged
                        .makeEffect(
                          HttpClientZioBackend.usingClient(
                            HttpClient
                              .newBuilder()
                              .followRedirects(HttpClient.Redirect.NORMAL)
                              .sslContext(sslContext)
                              .build()
                          )
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

  /** Layer producing a [[K8sCluster]] and an [[SttpClient]] module that can be directly used
    * to initialize specific Kubernetes client modules, using the [[defaultConfigChain]].
    */
  val k8sDefault: ZLayer[Blocking with System, Throwable, Has[K8sCluster] with SttpClient] =
    (Blocking.any ++ System.any) >+> defaultConfigChain >>> (k8sCluster ++ k8sSttpClient)
}
