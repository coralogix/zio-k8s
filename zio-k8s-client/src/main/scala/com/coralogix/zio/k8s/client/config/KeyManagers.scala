package com.coralogix.zio.k8s.client.config

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.{ PEMKeyPair, PEMParser }
import zio.system.System
import zio.{ system, ZIO, ZManaged }

import java.io.{ File, FileInputStream, InputStreamReader }
import java.security.KeyStore
import java.security.cert.{ CertificateFactory, X509Certificate }
import javax.net.ssl.{ KeyManager, KeyManagerFactory }

private object KeyManagers {

  private def getDefaultKeyStore: ZIO[System, Throwable, KeyStore] =
    for {
      propertyKeyStore    <- system.property("javax.net.ssl.keyStore")
      propertyKeyStoreFile = propertyKeyStore.map(new File(_))
      password            <- system.property("javax.net.ssl.keyStorePassword")
      defaultKeyStore     <- ZIO.effect(KeyStore.getInstance("JKS"))
      _                   <-
        propertyKeyStoreFile match {
          case Some(file) =>
            ZManaged.fromAutoCloseable(ZIO.effect(new FileInputStream(file))).use { stream =>
              ZIO.effect(defaultKeyStore.load(stream, password.getOrElse("changeit").toCharArray))
            }
          case None       =>
            ZIO.effect(defaultKeyStore.load(null))
        }
    } yield defaultKeyStore

  def apply(
    certificate: KeySource,
    key: KeySource,
    password: Option[String]
  ): ZIO[System, Throwable, Array[KeyManager]] =
    for {
      keyStore <- getDefaultKeyStore
      provider <- ZIO.effect(new BouncyCastleProvider())

      privateKey <- loadKeyStream(key).use { stream =>
                      ZIO.effect {
                        val pemKeyPair = new PEMParser(new InputStreamReader(stream))
                        val converter = new JcaPEMKeyConverter().setProvider(provider)
                        pemKeyPair.readObject() match {
                          case pair: PEMKeyPair   => converter.getPrivateKey(pair.getPrivateKeyInfo)
                          case pk: PrivateKeyInfo => converter.getPrivateKey(pk)
                          case other: Any         =>
                            throw new IllegalStateException(
                              s"Unexpected key pair type ${other.getClass.getSimpleName}"
                            )
                        }
                      }
                    }

      certificateFactory <- ZIO.effect(CertificateFactory.getInstance("X509"))
      x509Cert           <- loadKeyStream(certificate).use { stream =>
                              ZIO.effect(
                                certificateFactory.generateCertificate(stream).asInstanceOf[X509Certificate]
                              )
                            }

      _ <- ZIO.effect {
             keyStore.setKeyEntry(
               x509Cert.getIssuerX500Principal.getName,
               privateKey,
               password.getOrElse("changeit").toCharArray,
               Array(x509Cert)
             )
           }

      kmf <- ZIO.effect(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm))
      _   <- ZIO.effect(kmf.init(keyStore, "changeit".toCharArray))
    } yield kmf.getKeyManagers
}
