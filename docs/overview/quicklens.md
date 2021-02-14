---
id: overview_quicklens
title: "QuickLens support"
---

[Quicklens](https://github.com/softwaremill/quicklens) is a library that helps writing code that modified deeply nested data structures. This fits well some of the _Kubernetes resources_ that can consists of many layers of data structures, including
optional fields, lists and maps in the middle.

To enable the _Quicklens support_ of `zio-k8s` an additional dependency has to be imported:

```scala mdoc:passthrough

println(s"""```scala""")
if (zio.k8s.BuildInfo.isSnapshot)
  println(s"""resolvers += Resolver.sonatypeRepo("snapshots")""")
println(s"""libraryDependencies += "com.coralogix" %% "zio-k8s-client-quicklens" % "${zio.k8s.BuildInfo.version}"""")
println(s"""```""")

```

Then by importing both `com.softwaremill.quicklens._` and `com.coralogix.zio.k8s.quicklens._`, the library can be used as expected:

```scala mdoc:silent
import com.coralogix.zio.k8s.quicklens._
import com.coralogix.zio.k8s.model.core.v1.{ Container, Pod, PodSpec }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import com.softwaremill.quicklens._

val pod = Pod(
    metadata = ObjectMeta(
        name = "test-pod"
    ),
    spec = PodSpec(
        containers = Vector(
            Container(
                name = "test-container-1"
            )
        )
    )
)

val pod2 = pod.modify(_.metadata.each.namespace).setTo("namespace-1")

```
