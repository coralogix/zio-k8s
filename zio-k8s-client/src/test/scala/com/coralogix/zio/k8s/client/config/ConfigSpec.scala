package com.coralogix.zio.k8s.client.config

import sttp.client3._
import zio.config._
import zio.config.typesafe.TypesafeConfig
import zio.nio.core.file.Path
import zio.test.environment.TestEnvironment
import zio.test.{ assertM, Assertion, DefaultRunnableSpec, ZSpec }

object ConfigSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("K8sClusterConfig descriptors")(
      testM("example1") {
        // Loading config from HOCON
        val loadConfig =
          TypesafeConfig.fromHoconString[Config](example1, configDesc).build.useNow.map(_.get)

        assertM(loadConfig)(
          Assertion.equalTo(
            Config(
              K8sClusterConfig(
                uri"https://kubernetes.default.svc",
                authentication = K8sAuthentication.ServiceAccountToken(
                  token =
                    KeySource.FromFile(Path("/var/run/secrets/kubernetes.io/serviceaccount/token"))
                ),
                client = K8sClientConfig(
                  debug = false,
                  serverCertificate = K8sServerCertificate.Secure(
                    disableHostnameVerification = false,
                    certificate = KeySource.FromFile(
                      Path("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
                    )
                  )
                )
              )
            )
          )
        )
      }
    )

  case class Config(k8s: K8sClusterConfig)

  val configDesc: ConfigDescriptor[Config] =
    ConfigDescriptor.nested("k8s")(clusterConfigDescriptor).to[Config]

  val example1: String =
    """k8s {
      |  host = "https://kubernetes.default.svc"
      |  authentication {
      |    serviceAccountToken {
      |      path = "/var/run/secrets/kubernetes.io/serviceaccount/token"
      |    }
      |  }
      |  client {
      |    debug = false
      |    secure {
      |      certificate {
      |        path = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
      |      }
      |      disableHostnameVerification = false
      |    }
      |  }
      |}""".stripMargin
}
