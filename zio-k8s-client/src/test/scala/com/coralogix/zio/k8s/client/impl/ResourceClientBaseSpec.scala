package com.coralogix.zio.k8s.client.impl

import cats.data.NonEmptyList
import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.config.backend.K8sBackend
import com.coralogix.zio.k8s.client.model.{ K8sCluster, K8sResourceType }
import io.circe.Error
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3._
import sttp.model.StatusCode
import zio._
import zio.test._

import java.util.concurrent.atomic.AtomicInteger

object ResourceClientBaseSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] =
    suite("ResourceClientBase")(
      test("builds a fresh request on each access") {
        val index = new AtomicInteger(0)

        final class AuthorizationClient extends ResourceClientBase {
          override protected val resourceType: K8sResourceType = K8sResourceType("pods", "", "v1")
          override protected val cluster: K8sCluster =
            K8sCluster(
              uri"https://kubernetes.default.svc",
              Some(request => ZIO.succeed(request.auth.bearer(s"token-${index.incrementAndGet()}")))
            )
          override protected val backend: K8sBackend =
            K8sBackend(
              null.asInstanceOf[SttpBackend[Task, ZioStreams with WebSockets]]
            )

          def authorization: Task[Option[String]] =
            k8sRequest.map(
              _.headers
                .find(_.is("Authorization"))
                .map(_.value)
            )
        }
        val client = new AuthorizationClient

        assertZIO(client.authorization.zip(client.authorization))(
          Assertion.equalTo((Some("Bearer token-1"), Some("Bearer token-2")))
        )
      },
      test("retries once after unauthorized and token invalidation") {
        type ResponseBody = Either[ResponseException[String, NonEmptyList[Error]], String]

        val attempts = new AtomicInteger(0)
        val invalidations = new AtomicInteger(0)

        final class RetryClient extends ResourceClientBase {
          override protected val resourceType: K8sResourceType = K8sResourceType("pods", "", "v1")
          override protected val cluster: K8sCluster =
            K8sCluster(
              uri"https://kubernetes.default.svc",
              None,
              Some(ZIO.succeed(invalidations.incrementAndGet()).unit)
            )
          override protected val backend: K8sBackend =
            K8sBackend(
              null.asInstanceOf[SttpBackend[Task, ZioStreams with WebSockets]]
            )

          def run(request: Task[Response[ResponseBody]]): IO[K8sFailure, String] =
            handleFailures("get", None, "pod1")(request)
        }
        val client = new RetryClient

        val unauthorizedResponse: Response[ResponseBody] =
          Response(
            Left(HttpError("expired token", StatusCode.Unauthorized)),
            StatusCode.Unauthorized
          )
        val successfulResponse: Response[ResponseBody] =
          Response(Right("ok"), StatusCode.Ok)
        val requestTask: Task[Response[ResponseBody]] =
          ZIO.succeed {
            if (attempts.incrementAndGet() == 1) unauthorizedResponse else successfulResponse
          }

        assertZIO(
          client.run(requestTask).map(value => (value, attempts.get(), invalidations.get()))
        )(Assertion.equalTo(("ok", 2, 1)))
      }
    )
}
