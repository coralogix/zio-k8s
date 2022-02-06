package com.coralogix.zio.k8s.client.config

import io.circe.yaml.parser.parse
import sttp.client3._
import zio.{ Chunk, ZIO }
import zio.config._
import zio.config.typesafe.TypesafeConfig
import zio.nio.file.Path
import zio.test.environment.TestEnvironment
import zio.test.{ assertCompletes, assertM, Assertion, ZSpec }
import cats.implicits._
import com.coralogix.zio.k8s.client.config.K8sAuthentication.ServiceAccountToken
import com.coralogix.zio.k8s.client.config.KeySource.FromString
import zio.nio.file.Files

import java.nio.charset.StandardCharsets
import zio.test.ZIOSpecDefault

object ConfigSpec extends ZIOSpecDefault {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("K8sClusterConfig descriptors")(
      test("load config from string") {
        kubeconfigFromString(example2).as(assertCompletes)
      },
      test("load client config") {
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
      },
      test("parse kube config") {
        val kubeConfig = parseKubeConfigYaml(example2)

        assertM(kubeConfig)(
          Assertion.equalTo(
            Kubeconfig(
              clusters = List(
                KubeconfigCluster(
                  "test_cluster",
                  KubeconfigClusterInfo(
                    "https://127.0.0.1:696",
                    None,
                    "DDDDAAAANNNNYYYYMMMMOOOORRRR".some
                  )
                )
              ),
              contexts = List(
                KubeconfigContext(
                  name = "test",
                  context =
                    KubeconfigContextInfo("test_cluster", "test_user", "test_namespace".some)
                )
              ),
              users = List(
                KubeconfigUser(
                  "test_user",
                  KubeconfigUserInfo(
                    None,
                    None,
                    None,
                    None,
                    None,
                    None,
                    None,
                    ExecConfig(
                      apiVersion = "client.authentication.k8s.io/v1alpha1",
                      command = "echo",
                      env = Set(ExecEnv("BEARER_TOKEN", "bearer-token")).some,
                      args = List(
                        "{ \"apiVersion\": \"client.authentication.k8s.io/v1alpha1\", \"kind\": \"ExecCredential\", \"status\": {\"token\": \"bearer-token\" }}"
                      ).some,
                      None,
                      None
                    ).some
                  )
                )
              ),
              `current-context` = "test"
            )
          )
        )
      },
      test("run local config loading") {
        def createTempKubeConfigFile =
          for {
            path <- Files
                      .createTempFileManaged(prefix = "zio_k8s_test_".some)
            _    <- Files
                      .writeBytes(
                        path,
                        Chunk.fromArray(example2.getBytes(StandardCharsets.UTF_8))
                      )
                      .toManaged
          } yield path

        def loadTokenByCommand: ZIO[K8sClusterConfig, Throwable, Option[String]] =
          for {
            authentication <-
              ZIO.environment[K8sClusterConfig](_.get[K8sClusterConfig].authentication)
            result         <- authentication match {
                                case ServiceAccountToken(FromString(token)) =>
                                  ZIO.succeed(token.some)
                                case _                                      =>
                                  ZIO.none
                              }
          } yield result

        createTempKubeConfigFile.use(path =>
          assertM(for {
            configLayer <- ZIO.attempt(kubeconfigFile(path))
            maybeToken  <- loadTokenByCommand.provideLayer(configLayer)
          } yield maybeToken)(Assertion.equalTo(Some("bearer-token")))
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

  def parseKubeConfigYaml(yamlString: String) =
    for {
      yaml       <- ZIO.fromEither(parse(yamlString))
      kubeconfig <- ZIO.fromEither(yaml.as[Kubeconfig])
    } yield kubeconfig

  val example2: String =
    """apiVersion: v1
      |clusters:
      |- cluster:
      |    certificate-authority-data: DDDDAAAANNNNYYYYMMMMOOOORRRR
      |    server: https://127.0.0.1:696
      |  name: test_cluster
      |contexts:
      |- context:
      |    cluster: test_cluster
      |    namespace: test_namespace
      |    user: test_user
      |  name: test
      |current-context: test
      |kind: Config
      |preferences: {}
      |users:
      |- name: test_user
      |  user:
      |    exec:
      |      apiVersion: client.authentication.k8s.io/v1alpha1
      |      args:
      |      - "{ \"apiVersion\": \"client.authentication.k8s.io/v1alpha1\", \"kind\": \"ExecCredential\", \"status\": {\"token\": \"bearer-token\" }}"
      |      command: echo 
      |      env:
      |      - name: BEARER_TOKEN
      |        value: bearer-token
      |""".stripMargin
}
