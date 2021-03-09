---
id: overview_monocle
title: "Monocle support"
---

[Moncole](https://www.optics.dev/Monocle/) is an _optics library_ that helps writing code that modified deeply nested data structures. This fits well some of the _Kubernetes resources_ that can consists of many layers of data structures, including
optional fields, lists and maps in the middle.

To enable the _Monocle support_ of `zio-k8s` an additional dependency has to be imported:

```scala mdoc:passthrough

println(s"""```scala""")
if (com.coralogix.zio.k8s.BuildInfo.isSnapshot)
  println(s"""resolvers += Resolver.sonatypeRepo("snapshots")""")
println(s"""libraryDependencies += "com.coralogix" %% "zio-k8s-client-monocle" % "${com.coralogix.zio.k8s.BuildInfo.version}"""")
println(s"""```""")

```

This library defines a hierarchy of **optics** defined for the _Kubernetes resource types_. 
The following example demonstrates how to create a lens that can set the namespace inside the metadata section of a _pod_:

```scala mdoc:silent
import com.coralogix.zio.k8s.model.core.v1.{ Container, Pod, PodSpec }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import com.coralogix.zio.k8s.monocle.core.v1.PodO._
import com.coralogix.zio.k8s.monocle.pkg.apis.meta.v1.ObjectMetaO._

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

val f = (metadataO composeOptional namespaceO).set("namespace-1")

f(pod)
```
