package com.coralogix.zio.k8s.client.config

import cats.implicits._
import com.coralogix.zio.k8s.client.config.K8sAuthentication.ServiceAccountToken
import com.coralogix.zio.k8s.client.config.KeySource.FromString
import io.circe.yaml.parser.parse
import sttp.client3._
import zio.config.typesafe.TypesafeConfigProvider
import zio.nio.file.{ Files, Path }
import zio.test.{ assertCompletes, assertZIO, Assertion, Spec, TestEnvironment, ZIOSpecDefault }
import zio.{ Chunk, ZIO }

import java.nio.charset.StandardCharsets

object ConfigSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] =
    suite("K8sClusterConfig descriptors")(
      loadKubeConfigFromString,
      loadPKCS8KubeConfigFromString,
      loadRSAKubeConfigFromString,
      loadECKubeConfigFromString,
      clientConfigSpec,
      parseKubeConfig,
      runLocalConfigLoading
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

  val loadPKCS8KubeConfigFromString: Spec[TestEnvironment, Any] =
    test("load config with pkcs8 keys from string") {
      kubeconfigFromString(examplePKCS8Config).flatMap { kc =>
        SSL(kc.client.serverCertificate, kc.authentication).as(assertCompletes)
      }
    }

  val loadRSAKubeConfigFromString: Spec[TestEnvironment, Any] =
    test("load config with RSA key from string") {
      kubeconfigFromString(exampleRSAConfig).flatMap { kc =>
        SSL(kc.client.serverCertificate, kc.authentication).as(assertCompletes)
      }
    }

  val loadECKubeConfigFromString: Spec[TestEnvironment, Any] =
    test("load config with EC key from string") {
      kubeconfigFromString(exampleECConfig).flatMap { kc =>
        SSL(kc.client.serverCertificate, kc.authentication).as(assertCompletes)
      }
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
            case ServiceAccountToken(FromString(token)) =>
              ZIO.succeed(token.some)
            case _                                      =>
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

  val examplePKCS8Config: String =
    """
      |apiVersion: v1
      |clusters:
      |- cluster:
      |    certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUJnRENDQVNlZ0F3SUJBZ0lVVWFxMkJNMFhaazBVb001OENGRXh2aEk0TWp3d0NnWUlLb1pJemowRUF3SXcKSFRFYk1Ca0dBMVVFQXhNU1ZVTlFJRU5zYVdWdWRDQlNiMjkwSUVOQk1CNFhEVEU0TURNeE9URTFOVEF3TUZvWApEVEl6TURNeE9ERTFOVEF3TUZvd0hURWJNQmtHQTFVRUF4TVNWVU5RSUVOc2FXVnVkQ0JTYjI5MElFTkJNRmt3CkV3WUhLb1pJemowQ0FRWUlLb1pJemowREFRY0RRZ0FFa3pNY2JrNFRNc3lVcWcyYklKL050c2hCemxWcDcrenQKZ0trVHdHbGdYb09rZ3l3ckNBaU1YWnk4SG96dFE2NXJ3dDV1bUI1S0xXL3hSUi9vNExPclNxTkZNRU13RGdZRApWUjBQQVFIL0JBUURBZ0VHTUJJR0ExVWRFd0VCL3dRSU1BWUJBZjhDQVFJd0hRWURWUjBPQkJZRUZKc2g0cTlvCkpZV09vMGsxdGJqQlpDbkM1eFdvTUFvR0NDcUdTTTQ5QkFNQ0EwY0FNRVFDSURlMmpwR0ptWlNTL0tISGxmSnEKdnU5YXVzZCs5Nk5rR0g1SGFyWEN0azRtQWlCSnlUSUYyZk5aZ2xzZEc3USs0aG5TZ21EeEgzWUd0K0RjVzJiZwpiY0VlcFE9PQotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0tCi0tLS0tQkVHSU4gQ0VSVElGSUNBVEUtLS0tLQpNSUlCYWpDQ0FSQ2dBd0lCQWdJVVpaTTJPUFQwbTQxRGZDczFMRm5wYnNhL3hZb3dDZ1lJS29aSXpqMEVBd0l3CkV6RVJNQThHQTFVRUF4TUljM2RoY20wdFkyRXdIaGNOTVRnd016RTVNVFUxTURBd1doY05Nemd3TXpFME1UVTEKTURBd1dqQVRNUkV3RHdZRFZRUURFd2h6ZDJGeWJTMWpZVEJaTUJNR0J5cUdTTTQ5QWdFR0NDcUdTTTQ5QXdFSApBMElBQk5zVUo1YnhvRWZuNVVXS21TQ3Zoc3NlcDdubkpPa1dLUFVLaXgzSnhvbzlNNHp1WUVCdkpFV0VacmJnCmJyVWNPMHZyM3BWemxBUm83TXJZbk1MS09TbWpRakJBTUE0R0ExVWREd0VCL3dRRUF3SUJCakFQQmdOVkhSTUIKQWY4RUJUQURBUUgvTUIwR0ExVWREZ1FXQkJTdGhPTHVMSXNXL2pPOHcwSjJYM3hDM0FVY1FEQUtCZ2dxaGtqTwpQUVFEQWdOSUFEQkZBaUVBOTQwcGJxREJ6aGorTXNIMlhDUWRpUnJVQkFmTzVkV0YrdWFaUElnOHBHOENJSFF5ClNRQjhFS2wzcmZPVnpSOS9mU3FINm9kYVZQQk1GK3lqWk5VYnhFREgKLS0tLS1FTkQgQ0VSVElGSUNBVEUtLS0tLQo=
      |    server: https://horse.org:4443
      |  name: horse-cluster
      |contexts:
      |- context:
      |    cluster: horse-cluster
      |    namespace: chisel-ns
      |    user: rsa-user
      |  name: federal-context
      |current-context: federal-context
      |kind: Config
      |preferences:
      |  colors: true
      |users:
      |- name: rsa-user
      |  user:
      |    client-certificate-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUNTRENDQVRDZ0F3SUJBZ0lJRlJwWFNVQ0VkSTh3RFFZSktvWklodmNOQVFFTEJRQXdGVEVUTUJFR0ExVUUKQXhNS2EzVmlaWEp1WlhSbGN6QWVGdzB4T0RBek1Ea3hPVEk1TlRaYUZ3MHhPVEF6TURreE9USTVOVFphTURNeApGREFTQmdOVkJBb1RDMFJ2WTJ0bGNpQkpibU11TVJzd0dRWURWUVFERXhKa2IyTnJaWEl0Wm05eUxXUmxjMnQwCmIzQXdnWjh3RFFZSktvWklodmNOQVFFQkJRQURnWTBBTUlHSkFvR0JBTkZFRnRKT3VLS045VmtRKzJ5V0Z6d08KQUJPZ2hRM3lpSExBUkpQOHBxWHRDQ3VUV05weHdiUnM5TjlQcnhTbjBCblZzeXlreGlRNk12cHpLOWtDeWxBTgovWDZPbzFqWXgvK1BYdHp1NDAxc3VwbkhzSXI5S1VNQXhHVEdOK0NieXlRL3ZwTDlNSnVEV1VLUU1HYUtjNkFTCk5OdkEwVUVNWENQSTQrMHN0ZlFCQWdNQkFBR2pBakFBTUEwR0NTcUdTSWIzRFFFQkN3VUFBNElCQVFBOFdtNk4KdWk1cC9URlBURHRsczRpdm93cWlhbTR2MVM5aTVtMitSQXBCRUZralpXek0xVDhRZ2dUc1FsdDY2cGhYR0h2VwphenBKYzd3ajQzN082aURnQ0UwdXFiYmQ3bGRPNk1vb1Z6azNTaE5rU2YrUVNQd3dRdzlBQlRNR01JcC9qYzRFClk1S0Y1dG5iQTl6b3RTWUpid1JaVG1JQUVTSVQydFhKaWlyUFBLTXI3ekhTVkNpZVJWM1JmMWUwNFBCb3JnOUoKLzVoZGVzNDRUWEdiSSt3OURqaHV2ZGhRN0h2REdsdjZ6MmpsSy9hYXNxQXNoalFtVW9Hd0REelBsbGdkUm5adgp2cWd2WnovSVZNcVY5eEEzb2ZDOUwxUGF1ekFGdExjNHVZTFhFa1JsR2dFcVA2N2RjbVlxZFJWQXA4WkVBLzVqCk05aXFNdk11NnN5c3hTQWEKLS0tLS1FTkQgQ0VSVElGSUNBVEUtLS0tLQo=
      |    client-key-data: LS0tLS1CRUdJTiBQUklWQVRFIEtFWS0tLS0tCk1JR0hBZ0VBTUJNR0J5cUdTTTQ5QWdFR0NDcUdTTTQ5QXdFSEJHMHdhd0lCQVFRZ2s1UFVqN08waGJ5VWNscFIKMTArQWNod3d4ZjZabWZmZnEyYjNBUVJjWE5LaFJBTkNBQVFMNU4zYSt1eVZIcThrZ0wwMGZjeVhwTllhc2hUMAowZVd6WGFNbmFhWWszcklMSnkzVG5LaU9Gelh0RnhsT3hUcFFEdXVmSTVsMEdHbkFsNE9KTXNqMAotLS0tLUVORCBQUklWQVRFIEtFWS0tLS0tCg==
      |""".stripMargin

  val exampleECConfig: String =
    """
      |apiVersion: v1
      |clusters:
      |- cluster:
      |    certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUJnRENDQVNlZ0F3SUJBZ0lVVWFxMkJNMFhaazBVb001OENGRXh2aEk0TWp3d0NnWUlLb1pJemowRUF3SXcKSFRFYk1Ca0dBMVVFQXhNU1ZVTlFJRU5zYVdWdWRDQlNiMjkwSUVOQk1CNFhEVEU0TURNeE9URTFOVEF3TUZvWApEVEl6TURNeE9ERTFOVEF3TUZvd0hURWJNQmtHQTFVRUF4TVNWVU5RSUVOc2FXVnVkQ0JTYjI5MElFTkJNRmt3CkV3WUhLb1pJemowQ0FRWUlLb1pJemowREFRY0RRZ0FFa3pNY2JrNFRNc3lVcWcyYklKL050c2hCemxWcDcrenQKZ0trVHdHbGdYb09rZ3l3ckNBaU1YWnk4SG96dFE2NXJ3dDV1bUI1S0xXL3hSUi9vNExPclNxTkZNRU13RGdZRApWUjBQQVFIL0JBUURBZ0VHTUJJR0ExVWRFd0VCL3dRSU1BWUJBZjhDQVFJd0hRWURWUjBPQkJZRUZKc2g0cTlvCkpZV09vMGsxdGJqQlpDbkM1eFdvTUFvR0NDcUdTTTQ5QkFNQ0EwY0FNRVFDSURlMmpwR0ptWlNTL0tISGxmSnEKdnU5YXVzZCs5Nk5rR0g1SGFyWEN0azRtQWlCSnlUSUYyZk5aZ2xzZEc3USs0aG5TZ21EeEgzWUd0K0RjVzJiZwpiY0VlcFE9PQotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0tCi0tLS0tQkVHSU4gQ0VSVElGSUNBVEUtLS0tLQpNSUlCYWpDQ0FSQ2dBd0lCQWdJVVpaTTJPUFQwbTQxRGZDczFMRm5wYnNhL3hZb3dDZ1lJS29aSXpqMEVBd0l3CkV6RVJNQThHQTFVRUF4TUljM2RoY20wdFkyRXdIaGNOTVRnd016RTVNVFUxTURBd1doY05Nemd3TXpFME1UVTEKTURBd1dqQVRNUkV3RHdZRFZRUURFd2h6ZDJGeWJTMWpZVEJaTUJNR0J5cUdTTTQ5QWdFR0NDcUdTTTQ5QXdFSApBMElBQk5zVUo1YnhvRWZuNVVXS21TQ3Zoc3NlcDdubkpPa1dLUFVLaXgzSnhvbzlNNHp1WUVCdkpFV0VacmJnCmJyVWNPMHZyM3BWemxBUm83TXJZbk1MS09TbWpRakJBTUE0R0ExVWREd0VCL3dRRUF3SUJCakFQQmdOVkhSTUIKQWY4RUJUQURBUUgvTUIwR0ExVWREZ1FXQkJTdGhPTHVMSXNXL2pPOHcwSjJYM3hDM0FVY1FEQUtCZ2dxaGtqTwpQUVFEQWdOSUFEQkZBaUVBOTQwcGJxREJ6aGorTXNIMlhDUWRpUnJVQkFmTzVkV0YrdWFaUElnOHBHOENJSFF5ClNRQjhFS2wzcmZPVnpSOS9mU3FINm9kYVZQQk1GK3lqWk5VYnhFREgKLS0tLS1FTkQgQ0VSVElGSUNBVEUtLS0tLQo=
      |    server: https://horse.org:4443
      |  name: horse-cluster
      |contexts:
      |- context:
      |    cluster: horse-cluster
      |    namespace: chisel-ns
      |    user: ec-user
      |  name: federal-context
      |current-context: federal-context
      |kind: Config
      |preferences:
      |  colors: true
      |users:
      |- name: ec-user
      |  user:
      |    client-certificate-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUNCRENDQWF1Z0F3SUJBZ0lVSjBuWWwvU1UycWRmT2RUMUlGUW4rL2xhOU5vd0NnWUlLb1pJemowRUF3SXcKRXpFUk1BOEdBMVVFQXhNSWMzZGhjbTB0WTJFd0hoY05NVGd3TXpFNU1UY3hNREF3V2hjTk1qZ3dNekUyTVRjeApNREF3V2pCc01Ra3dCd1lEVlFRR0V3QXhDVEFIQmdOVkJBZ1RBREVKTUFjR0ExVUVCeE1BTVNnd0pnWURWUVFLCkV4OVBjbU5oT2lCMmIyTnROSEZpZDJadk1XdHNhalZ2Ykhwdk5UTnViR3R2TVE4d0RRWURWUVFMRXdaRGJHbGwKYm5ReERqQU1CZ05WQkFNVEJXRmtiV2x1TUZrd0V3WUhLb1pJemowQ0FRWUlLb1pJemowREFRY0RRZ0FFQytUZAoydnJzbFI2dkpJQzlOSDNNbDZUV0dySVU5TkhsczEyakoybW1KTjZ5Q3ljdDA1eW9qaGMxN1JjWlRzVTZVQTdyCm55T1pkQmhwd0plRGlUTEk5S09CZ3pDQmdEQU9CZ05WSFE4QkFmOEVCQU1DQmFBd0V3WURWUjBsQkF3d0NnWUkKS3dZQkJRVUhBd0l3REFZRFZSMFRBUUgvQkFJd0FEQWRCZ05WSFE0RUZnUVVZbkd3ZVpXVjZ4Mkl2YlFEWi9IUQpvS1dpekZzd0h3WURWUjBqQkJnd0ZvQVVyWVRpN2l5TEZ2NHp2TU5DZGw5OFF0d0ZIRUF3Q3dZRFZSMFJCQVF3CkFvRUFNQW9HQ0NxR1NNNDlCQU1DQTBjQU1FUUNJQmlSMnA5RUJRUDc1TEVsTUtXcEplQTc3aFZSTzA1V2VZN3QKQ3BjM0cwMEJBaUI0Um5odjJvZFUxWXB1Y25aMjNmWGFHTXN2aS9BaVhyekViOFE4M2lSeFNnPT0KLS0tLS1FTkQgQ0VSVElGSUNBVEUtLS0tLQo=
      |    client-key-data: LS0tLS1CRUdJTiBQUklWQVRFIEtFWS0tLS0tCk1JR0hBZ0VBTUJNR0J5cUdTTTQ5QWdFR0NDcUdTTTQ5QXdFSEJHMHdhd0lCQVFRZzJ2Z1NUYzl4NXFCWVp4d00KK044MHNtdklQOGpWdUYzOWdPRzl6NU41N1I2aFJBTkNBQVRzUDlVWTk2Sk1WNzBLUVh1V1lsTFhHQmFQcDBZWApzVUQ0MkFCTnZSMVkrM0tZTzJMalh0MXV2WGtUVloxVFd2VFNSZm1xZG5JcjJ3ajBGcXQzVkg1cgotLS0tLUVORCBQUklWQVRFIEtFWS0tLS0tCg==
      |""".stripMargin

  val exampleRSAConfig: String =
    """
      |apiVersion: v1
      |clusters:
      |- cluster:
      |    certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUJnRENDQVNlZ0F3SUJBZ0lVVWFxMkJNMFhaazBVb001OENGRXh2aEk0TWp3d0NnWUlLb1pJemowRUF3SXcKSFRFYk1Ca0dBMVVFQXhNU1ZVTlFJRU5zYVdWdWRDQlNiMjkwSUVOQk1CNFhEVEU0TURNeE9URTFOVEF3TUZvWApEVEl6TURNeE9ERTFOVEF3TUZvd0hURWJNQmtHQTFVRUF4TVNWVU5RSUVOc2FXVnVkQ0JTYjI5MElFTkJNRmt3CkV3WUhLb1pJemowQ0FRWUlLb1pJemowREFRY0RRZ0FFa3pNY2JrNFRNc3lVcWcyYklKL050c2hCemxWcDcrenQKZ0trVHdHbGdYb09rZ3l3ckNBaU1YWnk4SG96dFE2NXJ3dDV1bUI1S0xXL3hSUi9vNExPclNxTkZNRU13RGdZRApWUjBQQVFIL0JBUURBZ0VHTUJJR0ExVWRFd0VCL3dRSU1BWUJBZjhDQVFJd0hRWURWUjBPQkJZRUZKc2g0cTlvCkpZV09vMGsxdGJqQlpDbkM1eFdvTUFvR0NDcUdTTTQ5QkFNQ0EwY0FNRVFDSURlMmpwR0ptWlNTL0tISGxmSnEKdnU5YXVzZCs5Nk5rR0g1SGFyWEN0azRtQWlCSnlUSUYyZk5aZ2xzZEc3USs0aG5TZ21EeEgzWUd0K0RjVzJiZwpiY0VlcFE9PQotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0tCi0tLS0tQkVHSU4gQ0VSVElGSUNBVEUtLS0tLQpNSUlCYWpDQ0FSQ2dBd0lCQWdJVVpaTTJPUFQwbTQxRGZDczFMRm5wYnNhL3hZb3dDZ1lJS29aSXpqMEVBd0l3CkV6RVJNQThHQTFVRUF4TUljM2RoY20wdFkyRXdIaGNOTVRnd016RTVNVFUxTURBd1doY05Nemd3TXpFME1UVTEKTURBd1dqQVRNUkV3RHdZRFZRUURFd2h6ZDJGeWJTMWpZVEJaTUJNR0J5cUdTTTQ5QWdFR0NDcUdTTTQ5QXdFSApBMElBQk5zVUo1YnhvRWZuNVVXS21TQ3Zoc3NlcDdubkpPa1dLUFVLaXgzSnhvbzlNNHp1WUVCdkpFV0VacmJnCmJyVWNPMHZyM3BWemxBUm83TXJZbk1MS09TbWpRakJBTUE0R0ExVWREd0VCL3dRRUF3SUJCakFQQmdOVkhSTUIKQWY4RUJUQURBUUgvTUIwR0ExVWREZ1FXQkJTdGhPTHVMSXNXL2pPOHcwSjJYM3hDM0FVY1FEQUtCZ2dxaGtqTwpQUVFEQWdOSUFEQkZBaUVBOTQwcGJxREJ6aGorTXNIMlhDUWRpUnJVQkFmTzVkV0YrdWFaUElnOHBHOENJSFF5ClNRQjhFS2wzcmZPVnpSOS9mU3FINm9kYVZQQk1GK3lqWk5VYnhFREgKLS0tLS1FTkQgQ0VSVElGSUNBVEUtLS0tLQo=
      |    server: https://horse.org:4443
      |  name: horse-cluster
      |contexts:
      |- context:
      |    cluster: horse-cluster
      |    namespace: chisel-ns
      |    user: rsa-user
      |  name: federal-context
      |current-context: federal-context
      |kind: Config
      |preferences:
      |  colors: true
      |users:
      |- name: rsa-user
      |  user:
      |    client-certificate-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUNTRENDQVRDZ0F3SUJBZ0lJRlJwWFNVQ0VkSTh3RFFZSktvWklodmNOQVFFTEJRQXdGVEVUTUJFR0ExVUUKQXhNS2EzVmlaWEp1WlhSbGN6QWVGdzB4T0RBek1Ea3hPVEk1TlRaYUZ3MHhPVEF6TURreE9USTVOVFphTURNeApGREFTQmdOVkJBb1RDMFJ2WTJ0bGNpQkpibU11TVJzd0dRWURWUVFERXhKa2IyTnJaWEl0Wm05eUxXUmxjMnQwCmIzQXdnWjh3RFFZSktvWklodmNOQVFFQkJRQURnWTBBTUlHSkFvR0JBTkZFRnRKT3VLS045VmtRKzJ5V0Z6d08KQUJPZ2hRM3lpSExBUkpQOHBxWHRDQ3VUV05weHdiUnM5TjlQcnhTbjBCblZzeXlreGlRNk12cHpLOWtDeWxBTgovWDZPbzFqWXgvK1BYdHp1NDAxc3VwbkhzSXI5S1VNQXhHVEdOK0NieXlRL3ZwTDlNSnVEV1VLUU1HYUtjNkFTCk5OdkEwVUVNWENQSTQrMHN0ZlFCQWdNQkFBR2pBakFBTUEwR0NTcUdTSWIzRFFFQkN3VUFBNElCQVFBOFdtNk4KdWk1cC9URlBURHRsczRpdm93cWlhbTR2MVM5aTVtMitSQXBCRUZralpXek0xVDhRZ2dUc1FsdDY2cGhYR0h2VwphenBKYzd3ajQzN082aURnQ0UwdXFiYmQ3bGRPNk1vb1Z6azNTaE5rU2YrUVNQd3dRdzlBQlRNR01JcC9qYzRFClk1S0Y1dG5iQTl6b3RTWUpid1JaVG1JQUVTSVQydFhKaWlyUFBLTXI3ekhTVkNpZVJWM1JmMWUwNFBCb3JnOUoKLzVoZGVzNDRUWEdiSSt3OURqaHV2ZGhRN0h2REdsdjZ6MmpsSy9hYXNxQXNoalFtVW9Hd0REelBsbGdkUm5adgp2cWd2WnovSVZNcVY5eEEzb2ZDOUwxUGF1ekFGdExjNHVZTFhFa1JsR2dFcVA2N2RjbVlxZFJWQXA4WkVBLzVqCk05aXFNdk11NnN5c3hTQWEKLS0tLS1FTkQgQ0VSVElGSUNBVEUtLS0tLQo=
      |    client-key-data: LS0tLS1CRUdJTiBSU0EgUFJJVkFURSBLRVktLS0tLQpNSUlDWEFJQkFBS0JnUURSUkJiU1RyaWlqZlZaRVB0c2xoYzhEZ0FUb0lVTjhvaHl3RVNUL0thbDdRZ3JrMWphCmNjRzBiUFRmVDY4VXA5QVoxYk1zcE1Za09qTDZjeXZaQXNwUURmMStqcU5ZMk1mL2oxN2M3dU5OYkxxWng3Q0sKL1NsREFNUmt4amZnbThza1A3NlMvVENiZzFsQ2tEQm1pbk9nRWpUYndORkJERndqeU9QdExMWDBBUUlEQVFBQgpBb0dBU1daVGh1S2J1bENHalAzNjRpUm04K2FKT2xra01qY3VpdWxMWklqS3Z3bzd3bVVGVm1GdUt1WElvZ2NtCkJ0MnhqVTQ2Y1Y4K0xIakpaclU4M1Bvd2tXOHQycVE5aFdhZkdlbVY0bWhuYjFEYWNnRlNPMjZscytFT0NzODQKTDJONHR6UnpmTVFYZHd1cG56U1RCNjRsV1hDbjN3WS9kVm0yQUg5QlN2NVY2cDBDUVFEYmZMSlh1ZWlNRU1XOAptcE5zMVJBejE2MmhmWUxHVU5idFhCSk5xcm8xUTRtUS81enlCbWovUGFnSTROWWh3amxmSWZBbUVQcW5uanVHCmlqM0lKV29MQWtFQTlCUWE4R0dURnRkK2djczVzNmtOejFpbWdMR2FrRGRWcStHZitzaktvejc1WFg3c0tLeXkKTE1uVzE4ZzlKaGhSL3d1UzJzVlFNU1EwK2l6dld1NnRvd0pCQUo0M1V5L2R1WDVPRU53VjZUUEltcmRrUDZ0cgptRHR3eHAydmd4b3RlYkV2a0JqUHljakZTaWJEd1Q4MUkrYU41V0ZvUzM2Rk9zcGRTN2QrSzI3OVdXVUNRR2RpCjNNWlZqbWh1ZnplYlRhVzhSZzArRDhrVGNkVUVtMVZqRE5DOW5KZnBaTmNsbkFMZW85bzA1THdpSlVTdHFJM1AKNlRTaHY0WVJRQjk0U1NyTFR1RUNRQXRKOVYvVUg3WTl4cTlkd3JzVkZTM2FFTlFDTitsQThWQjBjcWZOSDlpUgpORFlZRTdGblJQV244VmlNdndsT3NzNmVUTjhIWGRrbXo2Yy8vV0NycENNPQotLS0tLUVORCBSU0EgUFJJVkFURSBLRVktLS0tLQo=
      |""".stripMargin
}
