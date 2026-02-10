package com.coralogix.zio.k8s.client.config

import cats.implicits._
import com.coralogix.zio.k8s.client.config.K8sAuthentication.ServiceAccountToken
import com.coralogix.zio.k8s.client.config.KeySource.FromString
import io.circe.yaml.parser.parse
import sttp.client3._
import zio.config.typesafe.TypesafeConfigProvider
import zio.nio.file.{ Files, Path }
import zio.test.{ assertCompletes, assertZIO, Assertion, Spec, TestClock, TestEnvironment, ZIOSpecDefault }
import zio._

import java.nio.charset.StandardCharsets

object ConfigSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] =
    suite("K8sClusterConfig descriptors")(
      loadKubeConfigFromString,
      clientConfigSpec,
      clientConfigWithTokenCacheSpec,
      parseKubeConfig,
      runLocalConfigLoading,
      reloadServiceAccountTokenFromFile,
      cacheServiceAccountTokenFromFileForConfiguredSeconds,
      refreshCachedTokenAfterCacheWindowElapsed
    )

  val parseKubeConfig: Spec[TestEnvironment, Any] = test("parse kube config") {
    val kubeConfig = parseKubeConfigYaml(example2)

    assertZIO(kubeConfig)(
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
              context = KubeconfigContextInfo("test_cluster", "test_user", "test_namespace".some)
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
  }

  val loadKubeConfigFromString: Spec[TestEnvironment, Any] = test("load config from string") {
    kubeconfigFromString(example2).as(assertCompletes)
  }

  val clientConfigSpec: Spec[zio.test.TestEnvironment, Any] = test("load client config") {
    // Loading config from HOCON
    val loadConfig = ZIO.scoped {
      TypesafeConfigProvider.fromHoconString(example1).load(configDesc)
    }

    assertZIO(loadConfig)(
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

  val clientConfigWithTokenCacheSpec: Spec[TestEnvironment, Any] =
    test("load client config with token cache") {
      val loadConfig = ZIO.scoped {
        TypesafeConfigProvider.fromHoconString(exampleWithTokenCache).load(configDesc)
      }

      assertZIO(loadConfig)(
        Assertion.equalTo(
          Config(
            K8sClusterConfig(
              uri"https://kubernetes.default.svc",
              authentication = K8sAuthentication.ServiceAccountToken(
                token = KeySource.FromFile(Path("/var/run/secrets/kubernetes.io/serviceaccount/token")),
                tokenCacheSeconds = 5
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

  val runLocalConfigLoading: Spec[TestEnvironment, Any] = test("run local config loading") {
    def createTempKubeConfigFile =
      for {
        path <- Files.createTempFileScoped(prefix = "zio_k8s_test_".some)
        _    <- Files
                  .writeBytes(
                    path,
                    Chunk.fromArray(example2.getBytes(StandardCharsets.UTF_8))
                  )

      } yield path

    def loadTokenByCommand: ZIO[K8sClusterConfig, Throwable, Option[String]] =
      for {
        result <-
          ZIO.environmentWithZIO[K8sClusterConfig](_.get.authentication match {
            case ServiceAccountToken(FromString(token), _) =>
              ZIO.succeed(token.some)
            case _                                         =>
              ZIO.none
          })
      } yield result

    val testIO = ZIO.scoped {
      createTempKubeConfigFile.flatMap { path =>
        for {
          configLayer <- ZIO.attempt(kubeconfigFile(path))
          maybeToken  <- loadTokenByCommand.provideLayer(configLayer)
        } yield maybeToken
      }
    }
    assertZIO(testIO)(Assertion.equalTo(Some("bearer-token")))
  }

  val reloadServiceAccountTokenFromFile: Spec[TestEnvironment, Any] =
    test("reload service account token from file") {
      val toChunk: String => Chunk[Byte] =
        s => Chunk.fromArray(s.getBytes(StandardCharsets.UTF_8))

      assertZIO(
        ZIO.scoped {
          Files
            .createTempFileScoped(prefix = "zio_k8s_test_token_".some)
            .flatMap { path =>
              for {
                _        <- Files.writeBytes(path, toChunk("token-1"))
                authData <- serviceAccountTokenAuthenticator(KeySource.FromFile(path))
                token1   <- authData
                              .authenticator(basicRequest)
                              .map(
                                _.headers
                                  .find(_.is("Authorization"))
                                  .map(_.value)
                              )
                _        <- Files.writeBytes(path, toChunk("token-2"))
                token2   <- authData
                              .authenticator(basicRequest)
                              .map(
                                _.headers
                                  .find(_.is("Authorization"))
                                  .map(_.value)
                              )
              } yield (token1, token2)
            }
        }
      )(Assertion.equalTo((Some("Bearer token-1"), Some("Bearer token-2"))))
    }

  val cacheServiceAccountTokenFromFileForConfiguredSeconds: Spec[TestEnvironment, Any] =
    test("cache service account token from file for configured seconds") {
      val toChunk: String => Chunk[Byte] =
        s => Chunk.fromArray(s.getBytes(StandardCharsets.UTF_8))

      assertZIO(
        ZIO.scoped {
          Files
            .createTempFileScoped(prefix = "zio_k8s_test_cached_token_".some)
            .flatMap { path =>
              for {
                _        <- Files.writeBytes(path, toChunk("token-1"))
                authData <- serviceAccountTokenAuthenticator(
                              KeySource.FromFile(path),
                              tokenCacheSeconds = 60
                            )
                token1   <- authData
                              .authenticator(basicRequest)
                              .map(
                                _.headers
                                  .find(_.is("Authorization"))
                                  .map(_.value)
                              )
                _        <- Files.writeBytes(path, toChunk("token-2"))
                token2   <- authData
                              .authenticator(basicRequest)
                              .map(
                                _.headers
                                  .find(_.is("Authorization"))
                                  .map(_.value)
                              )
              } yield (token1, token2)
            }
        }
      )(Assertion.equalTo((Some("Bearer token-1"), Some("Bearer token-1"))))
    }

  val refreshCachedTokenAfterCacheWindowElapsed: Spec[TestEnvironment, Any] =
    test("refresh cached token after cache window elapsed") {
      val toChunk: String => Chunk[Byte] =
        s => Chunk.fromArray(s.getBytes(StandardCharsets.UTF_8))

      assertZIO(
        ZIO.scoped {
          Files
            .createTempFileScoped(prefix = "zio_k8s_test_cached_token_refresh_".some)
            .flatMap { path =>
              for {
                _            <- Files.writeBytes(path, toChunk("token-1"))
                authData     <- serviceAccountTokenAuthenticator(
                                  KeySource.FromFile(path),
                                  tokenCacheSeconds = 5
                                )
                token1       <- authData
                                  .authenticator(basicRequest)
                                  .map(
                                    _.headers
                                      .find(_.is("Authorization"))
                                      .map(_.value)
                                  )
                _            <- Files.writeBytes(path, toChunk("token-2"))
                token2Before <- authData
                                  .authenticator(basicRequest)
                                  .map(
                                    _.headers
                                      .find(_.is("Authorization"))
                                      .map(_.value)
                                  )
                _            <- TestClock.adjust(6.seconds)
                token2After  <- authData
                                  .authenticator(basicRequest)
                                  .map(
                                    _.headers
                                      .find(_.is("Authorization"))
                                      .map(_.value)
                                  )
              } yield (token1, token2Before, token2After)
            }
        }
      )(
        Assertion.equalTo(
          (Some("Bearer token-1"), Some("Bearer token-1"), Some("Bearer token-2"))
        )
      )
    }

  case class Config(k8s: K8sClusterConfig)

  val configDesc: zio.Config[Config] = clusterConfigDescriptor.nested("k8s").map(Config)

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

  val exampleWithTokenCache: String =
    """k8s {
      |  host = "https://kubernetes.default.svc"
      |  authentication {
      |    serviceAccountToken {
      |      path = "/var/run/secrets/kubernetes.io/serviceaccount/token"
      |      tokenCacheSeconds = 5
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
