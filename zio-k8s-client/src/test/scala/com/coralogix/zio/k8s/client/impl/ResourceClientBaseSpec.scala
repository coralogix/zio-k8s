package com.coralogix.zio.k8s.client.impl

import com.coralogix.zio.k8s.client.model.{ K8sCluster, K8sResourceType }
import com.coralogix.zio.k8s.client.K8sFailure
import cats.data.NonEmptyList
import io.circe.Error
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3._
import sttp.model.StatusCode
import zio.{ IO, Task }
import zio.test.{ assertM, Assertion, DefaultRunnableSpec, ZSpec }
import zio.test.environment.TestEnvironment

import java.util.concurrent.atomic.AtomicInteger

object ResourceClientBaseSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("ResourceClientBase")(
      testM("builds a fresh request on each access") {
        val index = new AtomicInteger(0)

        final class AuthorizationClient extends ResourceClientBase {
          override protected val resourceType: K8sResourceType = K8sResourceType("pods", "", "v1")
          override protected val cluster: K8sCluster =
            K8sCluster(
              uri"https://kubernetes.default.svc",
              Some(request =>
                Task.succeed(request.auth.bearer(s"token-${index.incrementAndGet()}"))
              )
            )
          override protected val backend: SttpBackend[Task, ZioStreams with WebSockets] =
            null.asInstanceOf[SttpBackend[Task, ZioStreams with WebSockets]]

          def authorization: Task[Option[String]] =
            k8sRequest.map(
              _.headers
                .find(_.is("Authorization"))
                .map(_.value)
            )
        }
        val client = new AuthorizationClient

        assertM(client.authorization.zip(client.authorization))(
          Assertion.equalTo((Some("Bearer token-1"), Some("Bearer token-2")))
        )
      },
      testM("retries once after unauthorized and token invalidation") {
        type ResponseBody = Either[ResponseException[String, NonEmptyList[Error]], String]

        val attempts = new AtomicInteger(0)
        val invalidations = new AtomicInteger(0)

        final class RetryClient extends ResourceClientBase {
          override protected val resourceType: K8sResourceType = K8sResourceType("pods", "", "v1")
          override protected val cluster: K8sCluster =
            K8sCluster(
              uri"https://kubernetes.default.svc",
              None,
              Some(Task.effectTotal(invalidations.incrementAndGet()).unit)
            )
          override protected val backend: SttpBackend[Task, ZioStreams with WebSockets] =
            null.asInstanceOf[SttpBackend[Task, ZioStreams with WebSockets]]

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
          Task.effectTotal {
            if (attempts.incrementAndGet() == 1) unauthorizedResponse else successfulResponse
          }

        assertM(
          client.run(requestTask).map(value => (value, attempts.get(), invalidations.get()))
        )(Assertion.equalTo(("ok", 2, 1)))
      }
    )
}
