package com.coralogix.zio.k8s.client.config

import zio.{ System, ZIO }

import java.io.{ File, FileInputStream }
import java.security.{ KeyFactory, KeyStore, PrivateKey }
import java.security.spec.PKCS8EncodedKeySpec
import java.security.cert.{ CertificateFactory, X509Certificate }
import java.util.Base64
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
      keyStore <- getDefaultKeyStore

      pemData    <- ZIO.scoped(loadKeyStream(key) flatMap { stream =>
                      ZIO.attempt(scala.io.Source.fromInputStream(stream).mkString)
                    })
      privateKey <- extractKeyFromPEMData(pemData)

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

  /** This class encapsulates the PEM header/footer details for different key formats, used to
    * extract and parse the key using the correct algorithm,
    * @param header
    *   The expected PEM header for the key type
    * @param footer
    *   The expected PEM footer for the key type
    */
  case class PEMHeaderFooter(val header: String, val footer: String)

  val pkcs1PrivateKeyHdrFooter =
    PEMHeaderFooter("-----BEGIN RSA PRIVATE KEY-----", "-----END RSA PRIVATE KEY-----")
  val pkcs8PrivateKeyHdrFooter =
    PEMHeaderFooter("-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----")
  // openssl may generate following header/footer specifically for EC case
  val pkcs8ECPrivateKeyHdrFooter =
    PEMHeaderFooter("-----BEGIN EC PRIVATE KEY-----", "-----END EC PRIVATE KEY-----")

  trait PrivateKeyParser {
    val headerFooter: PEMHeaderFooter

    def getPrivateKey(keyBytes: Array[Byte]): ZIO[Any, Throwable, PrivateKey]

    protected def getPKCS8PrivateKey(
      keyBytes: Array[Byte],
      algo: String
    ): ZIO[Any, Throwable, PrivateKey] = for {
      spec       <- ZIO.attempt(new PKCS8EncodedKeySpec(keyBytes))
      factory    <- ZIO.attempt(KeyFactory.getInstance(algo))
      privateKey <- ZIO.attempt(factory.generatePrivate(spec))
    } yield privateKey
  }

  object PKCS1PrivateKeyParser extends PrivateKeyParser {
    override val headerFooter: PEMHeaderFooter = pkcs1PrivateKeyHdrFooter
    override def getPrivateKey(keyBytes: Array[Byte]) = {
      // java security API does not support pkcs#1 so convert to pkcs#8 RSA first
      val pkcs1Length = keyBytes.length;
      val totalLength = pkcs1Length + 22;
      val pkcs8Header: Array[Byte] = Array[Byte](
        0x30.toByte,
        0x82.toByte,
        ((totalLength >> 8) & 0xff).toByte,
        (totalLength & 0xff).toByte, // Sequence + total length
        0x2.toByte,
        0x1.toByte,
        0x0.toByte, // Integer (0)
        0x30.toByte,
        0xd.toByte,
        0x6.toByte,
        0x9.toByte,
        0x2a.toByte,
        0x86.toByte,
        0x48.toByte,
        0x86.toByte,
        0xf7.toByte,
        0xd.toByte,
        0x1.toByte,
        0x1.toByte,
        0x1.toByte,
        0x5.toByte,
        0x0.toByte, // Sequence: 1.2.840.113549.1.1.1, NULL
        0x4.toByte,
        0x82.toByte,
        ((pkcs1Length >> 8) & 0xff).toByte,
        (pkcs1Length & 0xff).toByte // Octet string + length
      )
      val pkcs8Bytes = pkcs8Header ++ keyBytes
      PKCS8PrivateKeyParser.getPrivateKey(pkcs8Bytes)
    }
  }
  object PKCS8PrivateKeyParser extends PrivateKeyParser {
    override val headerFooter: PEMHeaderFooter = pkcs8PrivateKeyHdrFooter
    override def getPrivateKey(keyBytes: Array[Byte]) = {
      // The PKCS#8 header doesn't specify the keys algo, so try RSA and EC in turn
      val algos = List("RSA", "EC")
      def tryWithAlgo(algo: String) = getPKCS8PrivateKey(keyBytes, algo).asSome
      ZIO
        .collectFirst(algos)(algo => tryWithAlgo(algo))
        .someOrFail(new Exception(s"Failed trying to parse PKCS8 private key using each of $algos"))
    }
  }

  object PKCS8ECPrivateKeyParser extends PrivateKeyParser {
    override val headerFooter: PEMHeaderFooter = pkcs8ECPrivateKeyHdrFooter
    override def getPrivateKey(keyBytes: Array[Byte]) = getPKCS8PrivateKey(keyBytes, "EC")
  }

  val privateKeyParsers =
    List(PKCS1PrivateKeyParser, PKCS8PrivateKeyParser, PKCS8ECPrivateKeyParser)

  private def findPrivateKeyParser(pemData: String): Option[PrivateKeyParser] =
    privateKeyParsers.find { parser =>
      pemData.startsWith(parser.headerFooter.header)
    }

  private def extractKeyFromPEMData(pemData: String): ZIO[Any, Throwable, PrivateKey] = for {
    parser          <- ZIO
                         .succeed(findPrivateKeyParser(pemData))
                         .someOrFail(new Exception("Private key not in a supported PEM format"))
    base64EncodedKey =
      pemData.replace(parser.headerFooter.header, "").replace(parser.headerFooter.footer, "")
    encoded         <- ZIO.attempt(Base64.getDecoder.decode(base64EncodedKey))
    key             <- parser.getPrivateKey(encoded)
  } yield key
}
