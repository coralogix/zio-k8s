package com.coralogix.zio.k8s.client.config

import sttp.client3.httpclient.zio._
import sttp.client3.logging.slf4j.Slf4jLoggingBackend
import zio.{ Has, ZLayer, ZManaged }

import java.net.http.HttpClient

package object httpclient {

  val k8sSttpClient: ZLayer[Has[K8sClientConfig], Throwable, SttpClient] =
    ZLayer.fromServiceManaged { config: K8sClientConfig =>
      for {
        sslContext <- (if (config.insecure)
                         insecureSSLContext()
                       else
                         secureSSLContext(config.cert)).toManaged_
        client     <- ZManaged
                        .makeEffect(
                          HttpClientZioBackend.usingClient(
                            HttpClient
                              .newBuilder()
                              .followRedirects(HttpClient.Redirect.NEVER)
                              .sslContext(sslContext)
                              .build()
                          )
                        )(_.close().ignore)
                        .map { backend =>
                          Slf4jLoggingBackend(
                            backend,
                            logRequestBody = config.debug,
                            logResponseBody = config.debug
                          )
                        }
      } yield client
    }
}
