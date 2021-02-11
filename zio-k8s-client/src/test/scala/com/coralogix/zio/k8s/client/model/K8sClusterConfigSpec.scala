package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.config.{ k8sCluster, K8sClusterConfig }
import sttp.client3.UriContext
import zio.{ ZIO, ZLayer }
import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.test.Assertion.{ anything, equalTo, fails, isSubtype }
import zio.test.environment.TestEnvironment
import zio.test._

import java.io.IOException

object K8sClusterConfigSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("K8sClusterConfig")(
      testM("specifying only token") {
        val config = ZLayer.succeed(
          K8sClusterConfig(
            host = uri"localhost",
            token = Some("test-token"),
            tokenFile = Path("/tmp/token")
          )
        )
        for {
          k8sCluster <- ZIO
                          .service[K8sCluster]
                          .provideSomeLayer[Blocking](Blocking.any ++ config >>> k8sCluster)
        } yield assert(k8sCluster.token)(equalTo("test-token"))
      },
      testM("file is used if token is not specified") {
        for {
          tmpPath    <- Files.createTempFile(prefix = None, fileAttributes = Nil)
          _          <- Files.writeLines(tmpPath, List("test-token-file"))
          config      = ZLayer.succeed(
                          K8sClusterConfig(
                            host = uri"localhost",
                            token = None,
                            tokenFile = tmpPath
                          )
                        )
          k8sCluster <- ZIO
                          .service[K8sCluster]
                          .provideSomeLayer[Blocking](Blocking.any ++ config >>> k8sCluster)
        } yield assert(k8sCluster.token)(equalTo("test-token-file\n"))
      },
      testM("file is used if token is specified but empty") {
        for {
          tmpPath    <- Files.createTempFile(prefix = None, fileAttributes = Nil)
          _          <- Files.writeLines(tmpPath, List("test-token-file"))
          config      = ZLayer.succeed(
                          K8sClusterConfig(
                            host = uri"localhost",
                            token = Some(""),
                            tokenFile = tmpPath
                          )
                        )
          k8sCluster <- ZIO
                          .service[K8sCluster]
                          .provideSomeLayer[Blocking](Blocking.any ++ config >>> k8sCluster)
        } yield assert(k8sCluster.token)(equalTo("test-token-file\n"))
      },
      testM("fails if no token specified and file does not exist") {
        val config = ZLayer.succeed(
          K8sClusterConfig(
            host = uri"localhost",
            token = None,
            tokenFile = Path("/tmp/nonexisting")
          )
        )
        val program = for {
          k8sCluster <- ZIO
                          .service[K8sCluster]
                          .provideSomeLayer[Blocking](Blocking.any ++ config >>> k8sCluster)
        } yield k8sCluster.token

        assertM(program.run)(
          fails[IOException](isSubtype[java.nio.file.NoSuchFileException](anything))
        )
      }
    )
}
