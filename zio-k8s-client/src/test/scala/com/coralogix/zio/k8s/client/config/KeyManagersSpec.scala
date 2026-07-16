package com.coralogix.zio.k8s.client.config

import com.coralogix.zio.k8s.client.config.KeySource.FromString
import zio.ZIO
import zio.test.{ assertTrue, Spec, TestEnvironment, ZIOSpecDefault }

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.spec.KeySpec
import java.security.{ Key, KeyFactory, KeyFactorySpi, PrivateKey, Provider, PublicKey, Security }
import java.security.cert.{ CertificateFactory, X509Certificate }
import java.util.concurrent.atomic.AtomicBoolean

object KeyManagersSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] =
    suite("KeyManagers")(
      test("builds key managers from PKCS#1 RSA private keys") {
        KeyManagers(
          FromString(rsaCertificate),
          FromString(rsaPkcs1PrivateKey),
          None
        ).map(keyManagers => assertTrue(keyManagers.nonEmpty))
      },
      test("builds key managers from SEC1 EC private keys") {
        KeyManagers(
          FromString(ecCertificate),
          FromString(ecSec1PrivateKey),
          None
        ).map(keyManagers => assertTrue(keyManagers.nonEmpty))
      },
      test("builds key managers with a custom key password") {
        KeyManagers(
          FromString(ecCertificate),
          FromString(ecSec1PrivateKey),
          Some("custom-password")
        ).map(keyManagers => assertTrue(keyManagers.nonEmpty))
      },
      test("rejects malformed traditional private keys") {
        for {
          publicKey <- loadPublicKey(rsaCertificate)
          result    <- ZIO
                         .scoped(
                           KeyManagers.loadPrivateKey(FromString(malformedRsaPrivateKey), publicKey)
                         )
                         .exit
        } yield assertTrue(result.isFailure)
      },
      test("uses registered JCA providers to decode private keys") {
        ZIO.scoped {
          ZIO
            .acquireRelease(
              ZIO.attempt {
                Security.removeProvider(TrackingProvider.Name)
                TrackingRsaKeyFactorySpi.prepare()
                val position = Security.addProvider(new TrackingProvider())
                if (position == -1) {
                  throw new IllegalStateException("Unable to register tracking JCA provider")
                }
              }
            )(_ => ZIO.succeed(Security.removeProvider(TrackingProvider.Name))) *>
            loadPublicKey(rsaCertificate).flatMap { publicKey =>
              KeyManagers
                .loadPrivateKey(
                  FromString(rsaPkcs8PrivateKey),
                  new TrackingPublicKey(publicKey)
                )
                .as(assertTrue(TrackingRsaKeyFactorySpi.wasUsed))
            }
        }
      }
    )

  private def loadPublicKey(certificate: String): ZIO[Any, Throwable, PublicKey] =
    ZIO.attempt {
      val stream = new ByteArrayInputStream(certificate.getBytes(StandardCharsets.US_ASCII))
      try
        CertificateFactory
          .getInstance("X509")
          .generateCertificate(stream)
          .asInstanceOf[X509Certificate]
          .getPublicKey
      finally stream.close()
    }

  private val ecCertificate =
    """-----BEGIN CERTIFICATE-----
      |MIIBIzCBygIJAK3oTMheLiVkMAoGCCqGSM49BAMCMBoxGDAWBgNVBAMMD3ppby1r
      |OHMtZWMtdGVzdDAeFw0yNjA3MTYyMDQxNDdaFw0zNjA3MTMyMDQxNDdaMBoxGDAW
      |BgNVBAMMD3ppby1rOHMtZWMtdGVzdDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IA
      |BGeggQAdGvOx7Y6QbkgNK2n+HlFpeqnJg0V1SwHV872ukwV249UksrDEpCDPSl6r
      |KvWUgLvVf6rLYZBnSV2TjDIwCgYIKoZIzj0EAwIDSAAwRQIhAM6mlmIo5YeY7+ac
      |5/w5su4TuXAx2Z42hZp9cbJnDPYSAiBKAl0mVyfmx/b/mf/tcuLxvSnObHqMmE4m
      |KpHq1I66JA==
      |-----END CERTIFICATE-----""".stripMargin

  private val ecSec1PrivateKey =
    """-----BEGIN EC PRIVATE KEY-----
      |MHcCAQEEILseYGKgpM5g5DZKkxHgTRLUthUuINPDmLg0iWJh35pEoAoGCCqGSM49
      |AwEHoUQDQgAEZ6CBAB0a87HtjpBuSA0raf4eUWl6qcmDRXVLAdXzva6TBXbj1SSy
      |sMSkIM9KXqsq9ZSAu9V/qsthkGdJXZOMMg==
      |-----END EC PRIVATE KEY-----""".stripMargin

  private val malformedRsaPrivateKey =
    """-----BEGIN RSA PRIVATE KEY-----
      |MAMCAQA=
      |-----END RSA PRIVATE KEY-----""".stripMargin

  private val rsaCertificate =
    """-----BEGIN CERTIFICATE-----
      |MIIBtzCCASACCQC5z+iCPMmYLzANBgkqhkiG9w0BAQsFADAgMR4wHAYDVQQDDBV6
      |aW8tazhzLXByb3ZpZGVyLXRlc3QwHhcNMjYwNzE2MTk0MDU0WhcNMzYwNzEzMTk0
      |MDU0WjAgMR4wHAYDVQQDDBV6aW8tazhzLXByb3ZpZGVyLXRlc3QwgZ8wDQYJKoZI
      |hvcNAQEBBQADgY0AMIGJAoGBAMsl74IENbnNryKYlZqLRzDzvkJhJCtaFb3xqmjE
      |1BQd2k52uPiNlw6C3Oi0mxltW5nBGG82t88N3EyfA4eMWVWSN0iRliNPBATPnCtG
      |kUBNBUfwUcE2Zl6uQXYNmne4s61em6erV7tEcGfZk40pwt6ZTGH+EUAqkDRPKYlo
      |7AifAgMBAAEwDQYJKoZIhvcNAQELBQADgYEAJF8mlhFjPq+YjKxGrwy21o3OaX4X
      |7NYT4DUGAA1cKHbs007ynBunnUDplM4s1vRljYvTbZMSpYAEJcGfkyrnuU3jhB6i
      |ufvvglJd53hBaX+uiqLU662ySDfpr//2JT2GuvtYYUEidM1uv4KmhHuRnBD4nNL4
      |Nc7oFmRlagZ81ME=
      |-----END CERTIFICATE-----""".stripMargin

  private val rsaPkcs1PrivateKey =
    """-----BEGIN RSA PRIVATE KEY-----
      |MIICXQIBAAKBgQDLJe+CBDW5za8imJWai0cw875CYSQrWhW98apoxNQUHdpOdrj4
      |jZcOgtzotJsZbVuZwRhvNrfPDdxMnwOHjFlVkjdIkZYjTwQEz5wrRpFATQVH8FHB
      |NmZerkF2DZp3uLOtXpunq1e7RHBn2ZONKcLemUxh/hFAKpA0TymJaOwInwIDAQAB
      |AoGBAMKCWiclHMQA2rXHX0cQIGQQnZU1KcqQgMzTvZR/EYkJZGNIbace+wmb5ySw
      |+OiJuvEm39xsieYooUyD3H9GtKjyB4/7/jAAsdf5svhBWnjvb5pSU+6wVGZA8JV4
      |gu/cOnD1z3064dYGRACWOgbFTAqB1wZjXzSiHwqcYrXiUtJhAkEA+C+YMU/lR/hX
      |cNQOZChv0QL331GhnA/y9/e7eBg/s2LyDLnGBGtGBhbEdbhY9butoqEBFqD9SU41
      |uQK9Lk1KuQJBANGLVQJkXd6yvMj/FSf7OY2Kkzn1nkJ08UqrtoQk1VpeCOf9AdCS
      |pv+6GEF0MjDNRMyopwH3XSwQbGGZkPab4hcCQFovuWdZ+CByDxxSArTEuPVD1d0R
      |5d83MHyJSld2wFcognq7W0ipzrVRuqxog/Mv8wXg6etWLxRfVkhXxXU44wkCQCiv
      |qhjlzggwolFQnhX+RKWD86Q8WbdDp5o9DxpHYJnESmxpBtItt3lN8+m5mwk4whQO
      |5yaNliy5H6IvxCLuD48CQQCMdOmtovE3cEX3w+ksmESA4iTEsuc4DFuJ7Sm5lSiW
      |MBjcsSSWK247TFla7J8ZNKKnX7lIrcHwBPBiTngwwePz
      |-----END RSA PRIVATE KEY-----""".stripMargin

  private val rsaPkcs8PrivateKey =
    """-----BEGIN PRIVATE KEY-----
      |MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAMsl74IENbnNryKY
      |lZqLRzDzvkJhJCtaFb3xqmjE1BQd2k52uPiNlw6C3Oi0mxltW5nBGG82t88N3Eyf
      |A4eMWVWSN0iRliNPBATPnCtGkUBNBUfwUcE2Zl6uQXYNmne4s61em6erV7tEcGfZ
      |k40pwt6ZTGH+EUAqkDRPKYlo7AifAgMBAAECgYEAwoJaJyUcxADatcdfRxAgZBCd
      |lTUpypCAzNO9lH8RiQlkY0htpx77CZvnJLD46Im68Sbf3GyJ5iihTIPcf0a0qPIH
      |j/v+MACx1/my+EFaeO9vmlJT7rBUZkDwlXiC79w6cPXPfTrh1gZEAJY6BsVMCoHX
      |BmNfNKIfCpxiteJS0mECQQD4L5gxT+VH+Fdw1A5kKG/RAvffUaGcD/L397t4GD+z
      |YvIMucYEa0YGFsR1uFj1u62ioQEWoP1JTjW5Ar0uTUq5AkEA0YtVAmRd3rK8yP8V
      |J/s5jYqTOfWeQnTxSqu2hCTVWl4I5/0B0JKm/7oYQXQyMM1EzKinAfddLBBsYZmQ
      |9pviFwJAWi+5Z1n4IHIPHFICtMS49UPV3RHl3zcwfIlKV3bAVyiCertbSKnOtVG6
      |rGiD8y/zBeDp61YvFF9WSFfFdTjjCQJAKK+qGOXOCDCiUVCeFf5EpYPzpDxZt0On
      |mj0PGkdgmcRKbGkG0i23eU3z6bmbCTjCFA7nJo2WLLkfoi/EIu4PjwJBAIx06a2i
      |8TdwRffD6SyYRIDiJMSy5zgMW4ntKbmVKJYwGNyxJJYrbjtMWVrsnxk0oqdfuUit
      |wfAE8GJOeDDB4/M=
      |-----END PRIVATE KEY-----""".stripMargin
}

final class TrackingProvider
    extends Provider(
      TrackingProvider.Name,
      "1.0",
      "Tracks provider-neutral private-key conversion"
    ) {
  put(s"KeyFactory.${TrackingProvider.Algorithm}", classOf[TrackingRsaKeyFactorySpi].getName)
}

object TrackingProvider {
  val Algorithm = "ZioK8sTestRSA"
  val Name = "zio-k8s-test-key-factory"
}

final class TrackingPublicKey(delegate: PublicKey) extends PublicKey {
  override def getAlgorithm: String = TrackingProvider.Algorithm

  override def getEncoded: Array[Byte] = delegate.getEncoded

  override def getFormat: String = delegate.getFormat
}

final class TrackingRsaKeyFactorySpi extends KeyFactorySpi {
  private lazy val delegate =
    KeyFactory.getInstance("RSA")

  override protected def engineGeneratePrivate(keySpec: KeySpec): PrivateKey = {
    TrackingRsaKeyFactorySpi.used.set(true)
    delegate.generatePrivate(keySpec)
  }

  override protected def engineGeneratePublic(keySpec: KeySpec): PublicKey =
    delegate.generatePublic(keySpec)

  override protected def engineGetKeySpec[T <: KeySpec](key: Key, keySpec: Class[T]): T =
    delegate.getKeySpec(key, keySpec)

  override protected def engineTranslateKey(key: Key): Key =
    delegate.translateKey(key)
}

object TrackingRsaKeyFactorySpi {
  private val used = new AtomicBoolean(false)

  def prepare(): Unit =
    used.set(false)

  def wasUsed: Boolean = used.get()
}
