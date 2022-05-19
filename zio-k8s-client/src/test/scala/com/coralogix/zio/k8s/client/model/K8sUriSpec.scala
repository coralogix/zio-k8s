package com.coralogix.zio.k8s.client.model

import sttp.client3.UriContext
import zio.test.Assertion._
import zio.test.{ ZIOSpecDefault, _ }
import zio._

object K8sUriSpec extends ZIOSpecDefault {
  private val cluster = K8sCluster(uri"https://localhost:32768", None)
  private val resourceType = K8sResourceType("rt", "gr", "v8")
  private val resourceTypeWithEmptyGroup = K8sResourceType("rt", "", "v8")
  private val ns = K8sNamespace("def")
  private val name = "n-123"

  override def spec: Spec[TestEnvironment, Any] =
    suite("K8sUriSpec")(
      suite("simple")(
        test("simple with name and namespace")(
          assert(K8sSimpleUri(resourceType, Some(name), None, Some(ns)).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123")
          )
        ),
        test("simple with namespace")(
          assert(K8sSimpleUri(resourceType, None, None, Some(ns)).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt")
          )
        ),
        test("simple with name")(
          assert(K8sSimpleUri(resourceType, Some(name), None, None).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt/n-123")
          )
        ),
        test("simple without name or namespace")(
          assert(K8sSimpleUri(resourceType, None, None, None).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt")
          )
        )
      ),
      suite("simple with empty group")(
        test("simple with name and namespace")(
          assert(
            K8sSimpleUri(resourceTypeWithEmptyGroup, Some(name), None, Some(ns)).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt/n-123")
          )
        ),
        test("simple with namespace")(
          assert(K8sSimpleUri(resourceTypeWithEmptyGroup, None, None, Some(ns)).toUri(cluster))(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt")
          )
        ),
        test("simple with name")(
          assert(K8sSimpleUri(resourceTypeWithEmptyGroup, Some(name), None, None).toUri(cluster))(
            equalTo(uri"https://localhost:32768/api/v8/rt/n-123")
          )
        ),
        test("simple without name or namespace")(
          assert(K8sSimpleUri(resourceTypeWithEmptyGroup, None, None, None).toUri(cluster))(
            equalTo(uri"https://localhost:32768/api/v8/rt")
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
        ),
        test("paginated with namespace and field selector")(
          assert(
            K8sPaginatedUri(
              resourceType,
              Some(ns),
              100,
              None,
              fieldSelector = Some(field("object.metadata") === "x")
            ).toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?limit=100&fieldSelector=object.metadata==x"
            )
          )
        ),
        test("paginated with namespace and label selector")(
          assert(
            K8sPaginatedUri(
              resourceType,
              Some(ns),
              100,
              None,
              labelSelector = Some(label("service").in("x", "y"))
            ).toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?limit=100&labelSelector=service in (x, y)"
            )
          )
        ),
        test("paginated with namespace and resource version")(
          assert(
            K8sPaginatedUri(
              resourceType,
              Some(ns),
              100,
              None,
              resourceVersion = ListResourceVersion.NotOlderThan("100")
            ).toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?limit=100&resourceVersion=100&resourceVersionMatch=NotOlderThan"
            )
          )
        )
      ),
      suite("paginated with empty group")(
        test("paginated without namespace or token")(
          assert(K8sPaginatedUri(resourceTypeWithEmptyGroup, None, 100, None).toUri(cluster))(
            equalTo(uri"https://localhost:32768/api/v8/rt?limit=100")
          )
        ),
        test("paginated with namespace but no token")(
          assert(K8sPaginatedUri(resourceTypeWithEmptyGroup, Some(ns), 100, None).toUri(cluster))(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt?limit=100")
          )
        ),
        test("paginated without namespace but token")(
          assert(
            K8sPaginatedUri(resourceTypeWithEmptyGroup, None, 100, Some("NEXT")).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt?limit=100&continue=NEXT")
          )
        ),
        test("paginated with namespace and token")(
          assert(
            K8sPaginatedUri(resourceTypeWithEmptyGroup, Some(ns), 100, Some("NEXT")).toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/api/v8/namespaces/def/rt?limit=100&continue=NEXT"
            )
          )
        )
      ),
      suite("modifier")(
        test("modifier without namespace")(
          assert(K8sModifierUri(resourceType, name, None, None, dryRun = false).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt/n-123")
          )
        ),
        test("modifier without namespace, dry run")(
          assert(K8sModifierUri(resourceType, name, None, None, dryRun = true).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt/n-123?dryRun=All")
          )
        ),
        test("modifier with namespace")(
          assert(K8sModifierUri(resourceType, name, None, Some(ns), dryRun = false).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123")
          )
        ),
        test("modifier with namespace, dry run")(
          assert(K8sModifierUri(resourceType, name, None, Some(ns), dryRun = true).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123?dryRun=All")
          )
        )
      ),
      suite("modifier with empty group")(
        test("modifier without namespace")(
          assert(
            K8sModifierUri(resourceTypeWithEmptyGroup, name, None, None, dryRun = false)
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt/n-123")
          )
        ),
        test("modifier without namespace, dry run")(
          assert(
            K8sModifierUri(resourceTypeWithEmptyGroup, name, None, None, dryRun = true)
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt/n-123?dryRun=All")
          )
        ),
        test("modifier with namespace")(
          assert(
            K8sModifierUri(resourceTypeWithEmptyGroup, name, None, Some(ns), dryRun = false)
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt/n-123")
          )
        ),
        test("modifier with namespace, dry run")(
          assert(
            K8sModifierUri(resourceTypeWithEmptyGroup, name, None, Some(ns), dryRun = true)
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt/n-123?dryRun=All")
          )
        )
      ),
      suite("deleting")(
        test("deleting without namespace")(
          assert(
            K8sDeletingUri(
              resourceType,
              name,
              None,
              None,
              dryRun = false,
              gracePeriod = None,
              propagationPolicy = None
            ).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt/n-123")
          )
        ),
        test("deleting without namespace, dry run")(
          assert(
            K8sDeletingUri(
              resourceType,
              name,
              None,
              None,
              dryRun = true,
              gracePeriod = None,
              propagationPolicy = None
            ).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt/n-123?dryRun=All")
          )
        ),
        test("deleting with namespace")(
          assert(
            K8sDeletingUri(
              resourceType,
              name,
              None,
              Some(ns),
              dryRun = false,
              gracePeriod = None,
              propagationPolicy = None
            ).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123")
          )
        ),
        test("deleting with namespace, dry run")(
          assert(
            K8sDeletingUri(
              resourceType,
              name,
              None,
              Some(ns),
              dryRun = true,
              gracePeriod = None,
              propagationPolicy = None
            ).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123?dryRun=All")
          )
        ),
        test("deleting with namespace, grace period and propagation policy")(
          assert(
            K8sDeletingUri(
              resourceType,
              name,
              None,
              Some(ns),
              dryRun = false,
              gracePeriod = Some(1.minute),
              propagationPolicy = Some(PropagationPolicy.Background)
            ).toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123?gracePeriodSeconds=60&propagationPolicy=Background"
            )
          )
        )
      ),
      suite("deleting with empty group")(
        test("deleting without namespace")(
          assert(
            K8sDeletingUri(
              resourceTypeWithEmptyGroup,
              name,
              None,
              None,
              dryRun = false,
              gracePeriod = None,
              propagationPolicy = None
            )
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt/n-123")
          )
        ),
        test("deleting without namespace, dry run")(
          assert(
            K8sDeletingUri(
              resourceTypeWithEmptyGroup,
              name,
              None,
              None,
              dryRun = true,
              gracePeriod = None,
              propagationPolicy = None
            )
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt/n-123?dryRun=All")
          )
        ),
        test("deleting with namespace")(
          assert(
            K8sDeletingUri(
              resourceTypeWithEmptyGroup,
              name,
              None,
              Some(ns),
              dryRun = false,
              gracePeriod = None,
              propagationPolicy = None
            )
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt/n-123")
          )
        ),
        test("deleting with namespace, dry run")(
          assert(
            K8sDeletingUri(
              resourceTypeWithEmptyGroup,
              name,
              None,
              Some(ns),
              dryRun = true,
              gracePeriod = None,
              propagationPolicy = None
            )
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt/n-123?dryRun=All")
          )
        ),
        test("deleting with namespace, graceful period and propagation policy")(
          assert(
            K8sDeletingUri(
              resourceTypeWithEmptyGroup,
              name,
              None,
              Some(ns),
              dryRun = false,
              gracePeriod = Some(10.seconds),
              propagationPolicy = Some(PropagationPolicy.Orphan)
            )
              .toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/api/v8/namespaces/def/rt/n-123?gracePeriodSeconds=10&propagationPolicy=Orphan"
            )
          )
        )
      ),
      suite("deleting many")(
        test("deleting many without namespace")(
          assert(
            K8sDeletingManyUri(
              resourceType,
              None,
              dryRun = false,
              gracePeriod = None,
              propagationPolicy = None,
              labelSelector = None,
              fieldSelector = None
            ).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt")
          )
        ),
        test("deleting many without namespace, dry run")(
          assert(
            K8sDeletingManyUri(
              resourceType,
              None,
              dryRun = true,
              gracePeriod = None,
              propagationPolicy = None,
              labelSelector = None,
              fieldSelector = None
            ).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt?dryRun=All")
          )
        ),
        test("deleting many with namespace")(
          assert(
            K8sDeletingManyUri(
              resourceType,
              Some(ns),
              dryRun = false,
              gracePeriod = None,
              propagationPolicy = None,
              labelSelector = None,
              fieldSelector = None
            ).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt")
          )
        ),
        test("deleting with namespace, dry run")(
          assert(
            K8sDeletingUri(
              resourceType,
              name,
              None,
              Some(ns),
              dryRun = true,
              gracePeriod = None,
              propagationPolicy = None
            ).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123?dryRun=All")
          )
        ),
        test("deleting many with namespace, grace period and propagation policy")(
          assert(
            K8sDeletingManyUri(
              resourceType,
              Some(ns),
              dryRun = false,
              gracePeriod = Some(1.minute),
              propagationPolicy = Some(PropagationPolicy.Background),
              labelSelector = None,
              fieldSelector = None
            ).toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?gracePeriodSeconds=60&propagationPolicy=Background"
            )
          )
        )
      ),
      suite("deleting many with empty group")(
        test("deleting many without namespace")(
          assert(
            K8sDeletingManyUri(
              resourceTypeWithEmptyGroup,
              None,
              dryRun = false,
              gracePeriod = None,
              propagationPolicy = None,
              labelSelector = None,
              fieldSelector = None
            )
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt")
          )
        ),
        test("deleting many without namespace, dry run")(
          assert(
            K8sDeletingManyUri(
              resourceTypeWithEmptyGroup,
              None,
              dryRun = true,
              gracePeriod = None,
              propagationPolicy = None,
              labelSelector = None,
              fieldSelector = None
            )
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt?dryRun=All")
          )
        ),
        test("deleting many with namespace")(
          assert(
            K8sDeletingManyUri(
              resourceTypeWithEmptyGroup,
              Some(ns),
              dryRun = false,
              gracePeriod = None,
              propagationPolicy = None,
              labelSelector = None,
              fieldSelector = None
            ).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt")
          )
        ),
        test("deleting many with namespace, dry run")(
          assert(
            K8sDeletingManyUri(
              resourceTypeWithEmptyGroup,
              Some(ns),
              dryRun = true,
              gracePeriod = None,
              propagationPolicy = None,
              labelSelector = None,
              fieldSelector = None
            )
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt?dryRun=All")
          )
        ),
        test("deleting many with namespace, graceful period and propagation policy")(
          assert(
            K8sDeletingManyUri(
              resourceTypeWithEmptyGroup,
              Some(ns),
              dryRun = false,
              gracePeriod = Some(10.seconds),
              propagationPolicy = Some(PropagationPolicy.Orphan),
              labelSelector = None,
              fieldSelector = None
            )
              .toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/api/v8/namespaces/def/rt?gracePeriodSeconds=10&propagationPolicy=Orphan"
            )
          )
        )
      ),
      suite("status modifier")(
        test("status modifier without namespace")(
          assert(
            K8sModifierUri(resourceType, name, Some("status"), None, dryRun = false).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt/n-123/status")
          )
        ),
        test("status modifier without namespace, dry run")(
          assert(
            K8sModifierUri(resourceType, name, Some("status"), None, dryRun = true).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt/n-123/status?dryRun=All")
          )
        ),
        test("status modifier with namespace")(
          assert(
            K8sModifierUri(resourceType, name, Some("status"), Some(ns), dryRun = false)
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123/status")
          )
        ),
        test("status modifier with namespace, dry run")(
          assert(
            K8sModifierUri(resourceType, name, Some("status"), Some(ns), dryRun = true)
              .toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt/n-123/status?dryRun=All"
            )
          )
        )
      ),
      suite("status modifier with empty group")(
        test("status modifier without namespace")(
          assert(
            K8sModifierUri(resourceTypeWithEmptyGroup, name, Some("status"), None, dryRun = false)
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt/n-123/status")
          )
        ),
        test("status modifier without namespace, dry run")(
          assert(
            K8sModifierUri(resourceTypeWithEmptyGroup, name, Some("status"), None, dryRun = true)
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt/n-123/status?dryRun=All")
          )
        ),
        test("status modifier with namespace")(
          assert(
            K8sModifierUri(
              resourceTypeWithEmptyGroup,
              name,
              Some("status"),
              Some(ns),
              dryRun = false
            )
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt/n-123/status")
          )
        ),
        test("status modifier with namespace, dry run")(
          assert(
            K8sModifierUri(
              resourceTypeWithEmptyGroup,
              name,
              Some("status"),
              Some(ns),
              dryRun = true
            )
              .toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/api/v8/namespaces/def/rt/n-123/status?dryRun=All"
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
      suite("creator with empty group")(
        test("creator without namespace")(
          assert(
            K8sCreatorUri(resourceTypeWithEmptyGroup, None, dryRun = false).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt")
          )
        ),
        test("creator without namespace, dry run")(
          assert(
            K8sCreatorUri(resourceTypeWithEmptyGroup, None, dryRun = true).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt?dryRun=All")
          )
        ),
        test("creator with namespace")(
          assert(
            K8sCreatorUri(resourceTypeWithEmptyGroup, Some(ns), dryRun = false).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt")
          )
        ),
        test("creator with namespace, dry run")(
          assert(
            K8sCreatorUri(resourceTypeWithEmptyGroup, Some(ns), dryRun = true).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt?dryRun=All")
          )
        )
      ),
      suite("watch")(
        test("watch without namespace or version")(
          assert(K8sWatchUri(resourceType, None, None, allowBookmarks = false).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt?watch=1")
          )
        ),
        test("watch without namespace but with version")(
          assert(
            K8sWatchUri(resourceType, None, Some("VER"), allowBookmarks = false).toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/apis/gr/v8/rt?watch=1&resourceVersion=VER")
          )
        ),
        test("watch with namespace but no version")(
          assert(K8sWatchUri(resourceType, Some(ns), None, allowBookmarks = false).toUri(cluster))(
            equalTo(uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?watch=1")
          )
        ),
        test("watch with namespace and version")(
          assert(
            K8sWatchUri(resourceType, Some(ns), Some("VER"), allowBookmarks = false).toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?watch=1&resourceVersion=VER"
            )
          )
        ),
        test("watch with namespace and field selector")(
          assert(
            K8sWatchUri(
              resourceType,
              Some(ns),
              None,
              allowBookmarks = true,
              fieldSelector = Some(field("object.metadata") === "x")
            ).toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?watch=1&fieldSelector=object.metadata==x&allowWatchBookmarks=true"
            )
          )
        ),
        test("watch with namespace and label selector")(
          assert(
            K8sWatchUri(
              resourceType,
              Some(ns),
              None,
              allowBookmarks = true,
              labelSelector = Some(label("service").in("x", "y"))
            ).toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/apis/gr/v8/namespaces/def/rt?watch=1&labelSelector=service in (x, y)&allowWatchBookmarks=true"
            )
          )
        )
      ),
      suite("watch with empty group")(
        test("watch without namespace or version")(
          assert(
            K8sWatchUri(resourceTypeWithEmptyGroup, None, None, allowBookmarks = false)
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt?watch=1")
          )
        ),
        test("watch without namespace but with version")(
          assert(
            K8sWatchUri(resourceTypeWithEmptyGroup, None, Some("VER"), allowBookmarks = false)
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/rt?watch=1&resourceVersion=VER")
          )
        ),
        test("watch with namespace but no version")(
          assert(
            K8sWatchUri(resourceTypeWithEmptyGroup, Some(ns), None, allowBookmarks = false)
              .toUri(cluster)
          )(
            equalTo(uri"https://localhost:32768/api/v8/namespaces/def/rt?watch=1")
          )
        ),
        test("watch with namespace and version")(
          assert(
            K8sWatchUri(resourceTypeWithEmptyGroup, Some(ns), Some("VER"), allowBookmarks = false)
              .toUri(cluster)
          )(
            equalTo(
              uri"https://localhost:32768/api/v8/namespaces/def/rt?watch=1&resourceVersion=VER"
            )
          )
        )
      )
    )
}
