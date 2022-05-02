package com.coralogix.zio.k8s.client.config

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.{PEMKeyPair, PEMParser}
import zio.{System, ZIO}

import java.io.{File, FileInputStream, InputStreamReader}
import java.security.KeyStore
import java.security.cert.{CertificateFactory, X509Certificate}
import javax.net.ssl.{KeyManager, KeyManagerFactory}

private object KeyManagers {

  private def getDefaultKeyStore: ZIO[System, Throwable, KeyStore] =
    for {
      propertyKeyStore    <- System.property("javax.net.ssl.keyStore")
      propertyKeyStoreFile = propertyKeyStore.map(new File(_))
      password            <- System.property("javax.net.ssl.keyStorePassword")
      defaultKeyStore     <- ZIO.attempt(KeyStore.getInstance("JKS"))
      _                   <-
        propertyKeyStoreFile match {
          case Some(file) =>
            ZIO.scoped(ZIO.fromAutoCloseable(ZIO.attempt(new FileInputStream(file))) flatMap { stream =>
              ZIO.attempt(defaultKeyStore.load(stream, password.getOrElse("changeit").toCharArray))
            })
          case None       =>
            ZIO.attempt(defaultKeyStore.load(null))
        }
    } yield defaultKeyStore

  def apply(
    certificate: KeySource,
    key: KeySource,
    password: Option[String]
  ): ZIO[System, Throwable, Array[KeyManager]] =
    for {
      keyStore <- getDefaultKeyStore
      provider <- ZIO.attempt(new BouncyCastleProvider())

      privateKey <- ZIO.scoped(loadKeyStream(key) flatMap { stream =>
                      ZIO.attempt {
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
                    })

      certificateFactory <- ZIO.attempt(CertificateFactory.getInstance("X509"))
      x509Cert           <- ZIO.scoped(loadKeyStream(certificate) flatMap { stream =>
                              ZIO.attempt(
                                certificateFactory.generateCertificate(stream).asInstanceOf[X509Certificate]
                              )
                            })

      _ <- ZIO.attempt {
             keyStore.setKeyEntry(
               x509Cert.getIssuerX500Principal.getName,
               privateKey,
               password.getOrElse("changeit").toCharArray,
               Array(x509Cert)
             )
           }

      kmf <- ZIO.attempt(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm))
      _   <- ZIO.attempt(kmf.init(keyStore, "changeit".toCharArray))
    } yield kmf.getKeyManagers
}
