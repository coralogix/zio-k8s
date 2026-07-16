package com.coralogix.zio.k8s.client.config

import zio.{ System, ZIO }

import java.io.{ File, FileInputStream }
import java.security.{ KeyStore, PrivateKey, PublicKey }
import java.security.cert.{ CertificateFactory, X509Certificate }
import javax.net.ssl.{ KeyManager, KeyManagerFactory }

private object KeyManagers {

  private def getDefaultKeyStore: ZIO[Any, Throwable, KeyStore] =
    for {
      propertyKeyStore    <- System.property("javax.net.ssl.keyStore")
      propertyKeyStoreFile = propertyKeyStore.map(new File(_))
      password            <- System.property("javax.net.ssl.keyStorePassword")
      defaultKeyStore     <- ZIO.attempt(KeyStore.getInstance("JKS"))
      _                   <-
        propertyKeyStoreFile match {
          case Some(file) =>
            ZIO.scoped(ZIO.fromAutoCloseable(ZIO.attempt(new FileInputStream(file))) flatMap {
              stream =>
                ZIO.attempt(
                  defaultKeyStore.load(stream, password.getOrElse("changeit").toCharArray)
                )
            })
          case None       =>
            ZIO.attempt(defaultKeyStore.load(null))
        }
    } yield defaultKeyStore

  def apply(
    certificate: KeySource,
    key: KeySource,
    password: Option[String]
  ): ZIO[Any, Throwable, Array[KeyManager]] =
    for {
      keyStore           <- getDefaultKeyStore
      keyPassword         = password.getOrElse("changeit").toCharArray
      certificateFactory <- ZIO.attempt(CertificateFactory.getInstance("X509"))
      x509Cert           <- ZIO.scoped(loadKeyStream(certificate) flatMap { stream =>
                              ZIO.attempt(
                                certificateFactory.generateCertificate(stream).asInstanceOf[X509Certificate]
                              )
                            })
      privateKey         <- ZIO.scoped(loadPrivateKey(key, x509Cert.getPublicKey))

      _ <- ZIO.attempt {
             keyStore.setKeyEntry(
               x509Cert.getIssuerX500Principal.getName,
               privateKey,
               keyPassword,
               Array(x509Cert)
             )
           }

      kmf <- ZIO.attempt(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm))
      _   <- ZIO.attempt(kmf.init(keyStore, keyPassword))
    } yield kmf.getKeyManagers

  private[config] def loadPrivateKey(
    key: KeySource,
    publicKey: PublicKey
  ): ZIO[zio.Scope, Throwable, PrivateKey] =
    loadKeyStream(key) flatMap { stream =>
      ZIO.attempt(PrivateKeyDecoder.decode(stream, publicKey))
    }
}
