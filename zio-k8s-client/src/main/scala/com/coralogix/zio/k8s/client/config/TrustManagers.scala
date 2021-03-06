package com.coralogix.zio.k8s.client.config

import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.{ system, Task, ZIO, ZManaged }
import zio.system.System

import java.io.{ File, FileInputStream, InputStream }
import java.security.KeyStore
import java.security.cert.{ CertificateFactory, X509Certificate }
import javax.net.ssl.{ TrustManager, TrustManagerFactory }

private object TrustManagers {

  private def getDefaultTrustStoreWithoutSecurityDir: Task[KeyStore] =
    ZIO.effect {
      val keyStore = KeyStore.getInstance("JKS")
      keyStore.load(null)
      keyStore
    }

  private def getDefaultTrustStoreWithSecurityDir(
    secDir: Path
  ): ZIO[Blocking with System, Throwable, KeyStore] =
    for {
      propertyTrustStore    <- system.property("javax.net.ssl.trustStore")
      propertyTrustStoreFile = propertyTrustStore.map(new File(_))
      password              <- system.property("javax.net.ssl.trustStorePassword")
      jssecacertsPath        = secDir / "jssecacerts"
      cacertsPath            = secDir / "cacerts"
      jscacertsExists       <-
        Files.exists(jssecacertsPath).zipWithPar(Files.isRegularFile(jssecacertsPath))(_ && _)

      finalFile = propertyTrustStoreFile
                    .orElse(if (jscacertsExists) Some(jssecacertsPath.toFile) else None)
                    .getOrElse(cacertsPath.toFile)

      keyStore <- ZIO.effect(KeyStore.getInstance("JKS"))
      _        <- ZManaged.fromAutoCloseable(ZIO.effect(new FileInputStream(finalFile))).use { stream =>
                    ZIO.effect(
                      keyStore.load(
                        stream,
                        password.getOrElse("changeit").toCharArray
                      )
                    )
                  }
    } yield keyStore

  private def getDefaultTrustStore: ZIO[System with Blocking, Throwable, KeyStore] =
    for {
      maybeJavaHome <- system.property("java.home")
      keyStore      <- maybeJavaHome match {
                         case Some(javaHome) =>
                           getDefaultTrustStoreWithSecurityDir(Path(javaHome) / "lib/security")
                         case None           => getDefaultTrustStoreWithoutSecurityDir
                       }
    } yield keyStore

  private def createTrustStore(
    pemInputStream: InputStream
  ): ZIO[System with Blocking, Throwable, KeyStore] =
    getDefaultTrustStore.flatMap { trustStore =>
      Task.effect {
        while (pemInputStream.available() > 0) {
          val certFactory = CertificateFactory.getInstance("X509")
          val cert = certFactory.generateCertificate(pemInputStream).asInstanceOf[X509Certificate]
          val alias = cert.getSubjectX500Principal.getName + "_" + cert.getSerialNumber.toString(16)
          trustStore.setCertificateEntry(alias, cert)
        }
        trustStore
      }
    }

  def apply(
    pemInputStream: InputStream
  ): ZIO[System with Blocking, Throwable, Array[TrustManager]] =
    createTrustStore(pemInputStream).flatMap { trustStore =>
      Task.effect {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
        tmf.init(trustStore)
        tmf.getTrustManagers
      }
    }
}
