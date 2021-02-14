package com.coralogix.zio.k8s.client.config

import sttp.client3.asynchttpclient.zio._
import sttp.client3.logging.slf4j.Slf4jLoggingBackend
import zio.{ Has, ZLayer, ZManaged }

import java.net.http.HttpClient
import javax.net.ssl.SSLContext
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.Dsl
import io.netty.handler.ssl.JdkSslContext
import io.netty.handler.ssl.ClientAuth

package object asynchttpclient {

  val k8sSttpClient: ZLayer[Has[K8sClientConfig], Throwable, SttpClient] =
    ZLayer.fromServiceManaged { config: K8sClientConfig =>
      for {
        runtime    <- ZManaged.runtime[Any]
        sslContext <- (if (config.insecure)
                         insecureSSLContext()
                       else
                         secureSSLContext(config.cert)).toManaged_
        client     <-
          ZManaged
            .makeEffect(
              AsyncHttpClientZioBackend.usingClient(
                runtime,
                Dsl.asyncHttpClient(
                  Dsl.config().setSslContext(new JdkSslContext(sslContext, true, ClientAuth.NONE))
                )
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
