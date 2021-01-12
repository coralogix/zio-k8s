package zio.k8s.client.model

import sttp.client3.UriContext
import zio.test._
import zio.test.Assertion._

object K8sUriSpec extends DefaultRunnableSpec {
  private val cluster = K8sCluster(uri"https://localhost:32768", "-")
  private val resourceType = K8sResourceType("rt", "gr", "v8")
  private val ns = K8sNamespace("def")
  private val name = "n-123"

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] =
    suite("K8sUriSpec")(
      suite("simple")(
        test("simple with name and namespace")(
          assert(K8sSimpleUri(resourceType, Some(name), Some(ns)).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123")
          )
        ),
        test("simple with namespace")(
          assert(K8sSimpleUri(resourceType, None, Some(ns)).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt")
          )
        ),
        test("simple with name")(
          assert(K8sSimpleUri(resourceType, Some(name), None).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt/n-123")
          )
        ),
        test("simple without name or namespace")(
          assert(K8sSimpleUri(resourceType, None, None).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt")
          )
        )
      ),
      suite("paginated")(
        test("paginated without namespace or token")(
          assert(K8sPaginatedUri(resourceType, None, 100, None).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt?limit=100")
          )
        ),
        test("paginated with namespace but no token")(
          assert(K8sPaginatedUri(resourceType, Some(ns), 100, None).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?limit=100")
          )
        ),
        test("paginated without namespace but token")(
          assert(K8sPaginatedUri(resourceType, None, 100, Some("NEXT")).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt?limit=100&continue=NEXT")
          )
        ),
        test("paginated with namespace and token")(
          assert(K8sPaginatedUri(resourceType, Some(ns), 100, Some("NEXT")).toUri(cluster))(
            equalTo(
              uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?limit=100&continue=NEXT"
            )
          )
        )
      ),
      suite("modifier")(
        test("modifier without namespace")(
          assert(K8sModifierUri(resourceType, name, None, dryRun = false).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt/n-123")
          )
        ),
        test("modifier without namespace, dry run")(
          assert(K8sModifierUri(resourceType, name, None, dryRun = true).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt/n-123?dryRun=All")
          )
        ),
        test("modifier with namespace")(
          assert(K8sModifierUri(resourceType, name, Some(ns), dryRun = false).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123")
          )
        ),
        test("modifier with namespace, dry run")(
          assert(K8sModifierUri(resourceType, name, Some(ns), dryRun = true).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123?dryRun=All")
          )
        )
      ),
      suite("status modifier")(
        test("status modifier without namespace")(
          assert(K8sStatusModifierUri(resourceType, name, None, dryRun = false).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt/n-123/status")
          )
        ),
        test("status modifier without namespace, dry run")(
          assert(K8sStatusModifierUri(resourceType, name, None, dryRun = true).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt/n-123/status?dryRun=All")
          )
        ),
        test("status modifier with namespace")(
          assert(K8sStatusModifierUri(resourceType, name, Some(ns), dryRun = false).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123/status")
          )
        ),
        test("status modifier with namespace, dry run")(
          assert(K8sStatusModifierUri(resourceType, name, Some(ns), dryRun = true).toUri(cluster))(
            equalTo(
              uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123/status?dryRun=All"
            )
          )
        )
      ),
      suite("creator")(
        test("creator without namespace")(
          assert(K8sCreatorUri(resourceType, None, dryRun = false).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt")
          )
        ),
        test("creator without namespace, dry run")(
          assert(K8sCreatorUri(resourceType, None, dryRun = true).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt?dryRun=All")
          )
        ),
        test("creator with namespace")(
          assert(K8sCreatorUri(resourceType, Some(ns), dryRun = false).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt")
          )
        ),
        test("creator with namespace, dry run")(
          assert(K8sCreatorUri(resourceType, Some(ns), dryRun = true).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?dryRun=All")
          )
        )
      ),
      suite("watch")(
        test("watch without namespace or version")(
          assert(K8sWatchUri(resourceType, None, None).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt?watch=1")
          )
        ),
        test("watch without namespace but with version")(
          assert(K8sWatchUri(resourceType, None, Some("VER")).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt?watch=1&resourceVersion=VER")
          )
        ),
        test("watch with namespace but no version")(
          assert(K8sWatchUri(resourceType, Some(ns), None).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?watch=1")
          )
        ),
        test("watch with namespace and version")(
          assert(K8sWatchUri(resourceType, Some(ns), Some("VER")).toUri(cluster))(
            equalTo(
              uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?watch=1&resourceVersion=VER"
            )
          )
        )
      )
    )
}
