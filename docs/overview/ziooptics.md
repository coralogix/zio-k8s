---
id: overview_ziooptics
title: "ZIO Optics support"
---

[ZIO Optics](https://zio.github.io/zio-optics/) is an _optics library_ for the ZIO ecosystem that helps writing code that modifies deeply nested data structures. This fits well some of the _Kubernetes resources_ that can consists of many layers of data structures, including
optional fields, lists and maps in the middle.

To enable the _ZIO Optics support_ of `zio-k8s` an additional dependency has to be imported:

```scala mdoc:passthrough

println(s"""```scala""")
if (com.coralogix.zio.k8s.BuildInfo.isSnapshot)
  println(s"""resolvers += Resolver.sonatypeRepo("snapshots")""")
println(s"""libraryDependencies += "com.coralogix" %% "zio-k8s-client-optics" % "${com.coralogix.zio.k8s.BuildInfo.version}"""")
println(s"""```""")

```

This library defines a hierarchy of **optics** defined for the _Kubernetes resource types_.
The following example demonstrates how to create a lens that can set the namespace inside the metadata section of a _pod_:

```scala mdoc:silent
import com.coralogix.zio.k8s.model.core.v1.{ Container, Pod, PodSpec }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import com.coralogix.zio.k8s.optics.core.v1.PodO._
import com.coralogix.zio.k8s.optics.pkg.apis.meta.v1.ObjectMetaO._

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

val f = (metadataO >>> namespaceO).set("namespace-1")(_)

f(pod)
```
