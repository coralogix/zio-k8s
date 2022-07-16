package com.coralogix.zio.k8s.client.config

import zio.{ System, Task, ZIO }

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.{ KeyManager, SSLContext, TrustManager, X509TrustManager }

object SSL {
  def apply(
    serverCertificate: K8sServerCertificate,
    authentication: K8sAuthentication
  ): ZIO[Any, Throwable, SSLContext] =
    serverCertificate match {
      case K8sServerCertificate.Insecure               =>
        insecureSSLContext()
      case K8sServerCertificate.Secure(certificate, _) =>
        secureSSLContext(certificate, authentication)
    }

  private def insecureSSLContext(): Task[SSLContext] = {
    val trustAllCerts = Array[TrustManager](new X509TrustManager {
      override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = {}
      override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = {}
      override def getAcceptedIssuers: Array[X509Certificate] = null
    })
    ZIO.attempt {
      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      sslContext.init(null, trustAllCerts, new SecureRandom())
      sslContext
    }
  }

  private def secureSSLContext(
    certSource: KeySource,
    authentication: K8sAuthentication
  ): ZIO[Any, Throwable, SSLContext] =
    ZIO.scoped(loadKeyStream(certSource) flatMap { certStream =>
      for {
        keyManagers   <-
          authentication match {
            case K8sAuthentication.ServiceAccountToken(_)                         => ZIO.none
            case K8sAuthentication.BasicAuth(_, _)                                => ZIO.none
            case K8sAuthentication.ClientCertificates(certificate, key, password) =>
              KeyManagers(certificate, key, password).map(Some(_))
          }
        trustManagers <- TrustManagers(certStream)
        sslContext    <- createSslContext(keyManagers, trustManagers)
      } yield sslContext
    })

  private def createSslContext(
    keyManagers: Option[Array[KeyManager]],
    trustManagers: Array[TrustManager]
  ): Task[SSLContext] =
    ZIO.attempt {
      val sslContext = SSLContext.getInstance("TLSv1.2")
      sslContext.init(keyManagers.orNull, trustManagers, new SecureRandom())
      sslContext
    }
}
