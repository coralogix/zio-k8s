---
id: overview_resources
title: "Working with resources"
---

## Per-resource layers
The [getting started](gettingstarted.md) page demonstrated how to get access **per-resource client layers**, 
for example _pods_ and _configmaps_:

```scala mdoc:invisible
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import sttp.client3._
import sttp.model._
import zio._
import zio.blocking.Blocking
import zio.nio.core.file.Path

// Configuration
val clientConfig = K8sClientConfig(
    insecure = false, // set true for testing locally with minikube
    debug = false,
    cert = Path("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
)

val clusterConfig = K8sClusterConfig(
    host = uri"https://kubernetes.default.svc",
    token = None,
    tokenFile = Path("/var/run/secrets/kubernetes.io/serviceaccount/token")
)
val client = ZLayer.succeed(clientConfig) >>> k8sSttpClient
val cluster = Blocking.any ++ ZLayer.succeed(clusterConfig) >>> k8sCluster
```

```scala mdoc:silent
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.client.K8sFailure

val k8s: ZLayer[Blocking, Throwable, Pods with ConfigMaps] = 
  (cluster ++ client) >>> (Pods.live ++ ConfigMaps.live)
```

With this approach we gain a clear understanding of exactly what parts our application
uses of the _Kubernetes API_, and for each function the _type signature_ documents 
which resources the given function works with.

As an example, the above created `Pods with ConfigMaps` could be provided to some
functions working with a subset of these resource types:

```scala mdoc:silent
def launchNewPods(count: Int): ZIO[Pods, K8sFailure, Unit] = ZIO.unit // ...
def getFromConfigMap(key: String): ZIO[ConfigMaps, K8sFailure, String] = ZIO.succeed("TODO") // ...

launchNewPods(5).provideCustomLayer(k8s)
getFromConfigMap("something").provideCustomLayer(k8s)
```

when using this style, use the _accessor functions_ that are available for every resource in its
package. This is explained in details below, but as an example, see the following function from
the `com.coralogix.zio.k8s.client.v1.pods` package:

```scala
def create(newResource: Pod, namespace: K8sNamespace, dryRun: Boolean = false): ZIO[Pods, K8sFailure, Pod]
```

## Unified layer
An alternative style is supported by the library, recommended in cases when there are so many different
resource types used by the application logic that creating and specifying the per-resource layers creates
too much boilerplate.

In this case it is possible to create a _single, unfied Kubernetes API layer_:

```scala mdoc:silent
import com.coralogix.zio.k8s.client.kubernetes.Kubernetes

val api = (cluster ++ client) >>> Kubernetes.live
```

This is a huge interface providing _all operations_ for _all Kubernetes resources_. The 
initialization and the type signatures becomes much simpler, but on the other hand 
we loose the ability to see what parts of the API does our functions use:

```scala mdoc:silent
def launchNewPods2(count: Int): ZIO[Kubernetes, K8sFailure, Unit] = 
  ZIO.service[Kubernetes.Service].flatMap { k8s => 
    // ...
    ZIO.unit
  }

def getFromConfigMap2(key: String): ZIO[Kubernetes, K8sFailure, String] = 
  ZIO.service[Kubernetes.Service].flatMap { k8s => 
    // ...
    ZIO.succeed("TODO")
  }

launchNewPods2(5).provideCustomLayer(api)
getFromConfigMap2("something").provideCustomLayer(api)
```

Also, instead of the _accessor functions_ like `pods.create` shown in the previous section,
in this case the pod creation is accessed through the `Kubernetes.Service` interface:

```scala
ZIO.service[Kubernetes.Service].flatMap { k8s => 
  k8s.v1.pods.create(...)
}
```

### Narrowing the unified layer
The two styles - per-resource layers and the unified API layer - can be mixed together. 
If we have initialized the single unified API layer like above, called `api`, we can still
provide it for functions that have per-resource layer requirements:

```scala mdoc:silent
launchNewPods(5).provideCustomLayer(api.project(_.v1.pods))
getFromConfigMap("something").provideCustomLayer(api.project(_.v1.configmaps))
```

## Operations

Each _resource client_ provides a set of **operations** and depending on the resource, some 
additional capabilities related to _subresources_. 

Let's see what the supported operations by looking at an example, the `StatefulSet` resource!

The following functions are the _basic operations_ the resource supports:

#### Get
```scala
def getAll(namespace: Option[K8sNamespace], chunkSize: Int = 10): ZStream[StatefulSets, K8sFailure, StatefulSet]
def get(name: String, namespace: K8sNamespace): ZIO[StatefulSets, K8sFailure, StatefulSet]
```

- `getAll` returns a **stream** of all resources in the cluster, optionally filtered to a single _namespace_.
- `get` returns a single `StatefulSet` from a given _namespace_

#### Watch
```scala
def watch(namespace: Option[K8sNamespace], resourceVersion: Option[String]): ZStream[StatefulSets, K8sFailure, TypedWatchEvent[StatefulSet]]
def watchForever(namespace: Option[K8sNamespace]): ZStream[StatefulSets with Clock, K8sFailure, TypedWatchEvent[StatefulSet]]
```

- `watch` starts a **stream** of _watch events_ starting from a given resource version. The lifecycle of this stream corresponds with the underlying Kubernetes API request.
- `watchForever` is built on top of `watch` and handles reconnection when the underlying connection closes.

A watch event is one of the following:

- `Reseted` when the underlying watch stream got restarted. If the watch stream is processed in a stateful way, the state must be rebuilt from scratch when this event arrives.
- `Added`
- `Modified`
- `Deleted`

#### Create
```scala
def create(
    newResource: StatefulSet, 
    namespace: K8sNamespace, 
    dryRun: Boolean = false
  ): ZIO[StatefulSets, K8sFailure, StatefulSet]
```

- `create` creates a new _Kubernetes resource_ in the given namespace. The [model section below](#model) describes how to assemble the resource data.

#### Replace
```scala
def replace(
    name: String,
    updatedResource: StatefulSet,
    namespace: K8sNamespace,
    dryRun: Boolean = false
  ): ZIO[StatefulSets, K8sFailure, StatefulSet]
```

- `replace` updates an existing _Kubernetes resource_ identified by its name in the given namespace with the new value.

#### Delete

```scala
def delete(
    name: String,
    deleteOptions: DeleteOptions,
    namespace: K8sNamespace,
    dryRun: Boolean = false
  ): ZIO[StatefulSets, K8sFailure, Status]
```

- `delete` deletes an existing _Kubernetes resource_ identified by its name in the given namespace.

### Namespaced vs cluster resources
Some _Kubernetes resources_ are **cluster level** while others like the example `StatefulSet` above are split in _namespaces_. The `zio-k8s` library encodes this property in the resource interfaces, and for _cluster resources_ the operations does not
have a `namespace` parameter at all.

### Status subresource
Most of the resources have a _status subresource_. This capability is provided by the following extra functions:

```scala
def getStatus(name: String, namespace: K8sNamespace): ZIO[StatefulSets, K8sFailure, StatefulSet]

def replaceStatus(
    of: StatefulSet,
    updatedStatus: StatefulSetStatus,
    namespace: K8sNamespace,
    dryRun: Boolean = false
  ): ZIO[StatefulSets, K8sFailure, StatefulSet]
```

Note that although that currently the Scala interface closely reflects the underlying HTTP API's behavior and for this reason the type of these functions can be a bit surprising:

- `getStatus` returns the _whole resource_ not just the status
- `replaceStatus` requires the _whole resource_ to be updated but _Kubernetes_ will only update the status part while using the _metadata_ part for collision detection.

### Other subresources
Some resources have _additional subresources_. Our example, the `StatefulSet` has one, the `Scale` subresource.

For each subresource a set of additional operations are provided, in the example case a get/replace pair:

```scala
def getScale(
    name: String,
    namespace: K8sNamespace
  ): ZIO[StatefulSets, K8sFailure, Scale]
  
def replaceScale(
    name: String,
    updatedValue: Scale,
    namespace: K8sNamespace,
    dryRun: Boolean = false
  ): ZIO[StatefulSets, K8sFailure, autoscaling.v1.Scale]
```

Some important things to note:

- The subresource API does not share the weird properties of the status subresource API. The main resource is identified by its name (and namespace), and only the subresource data type get sent in the request.
- Not all subresources have a `get` and a `replace` operation. Some only have a `create` (for example `Eviction`), and some only have a `get` (for example `Log`).

## Model

For all _Kubernetes data types_ - the resources, subresources and inner data structures - there is a corresponding  Scala _case class_ defined in the `zio-k8s-client` library. Because a huge part of the model consists of _optional fields_ and very deep structures, a couple of features were added to reduce boilerplate caused by this.

Let's take a look at the example `StatefulSet` resource's data model:

```scala
case class StatefulSet(
  metadata: Optional[ObjectMeta] = Optional.Absent,
  spec: Optional[StatefulSetSpec] = Optional.Absent,
  status: Optional[StatefulSetStatus] = Optional.Absent
) {
  def getMetadata: IO[K8sFailure, ObjectMeta]
  def getSpec: IO[K8sFailure, StatefulSetSpec]
  def getStatus: IO[K8sFailure, StatefulSetStatus]
}
```

- Instead of the standard `Option` type, `zio-k8s` uses a custom `Optional` type
- Additionally to the case class fields it has ZIO getter functions that fail in case of absence of value

### Creating 
The custom `Optional[T]` type used in the model classes provides implicit conversion from both `T` and `Option[T]`. This provides a boilerplate-free way to specify large Kubernetes resources, with a syntax that is not far from to the usual YAML representation of _Kubernetes resources_.

The following example demonstrates this:

```scala mdoc
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.model.core.v1._
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1._
import com.coralogix.zio.k8s.model.rbac.v1._

def clusterRoleBinding(name: String, namespace: K8sNamespace): ClusterRoleBinding =
  ClusterRoleBinding(
    metadata = ObjectMeta(
        name = "fluentd-coralogix-role-binding",
        namespace = namespace.value,
        labels = Map(
            "k8s-app" -> s"fluentd-coralogix-$name"
        )
    ),
    roleRef = RoleRef(
        apiGroup = "rbac.authorization.k8s.io",
        kind = "ClusterRole",
        name = "fluentd-coralogix-role"
    ),
    subjects = Vector(
        Subject(
            kind = "ServiceAccount",
            name = "fluentd-coralogix-service-account",
            namespace = namespace.value
        )
    ))
```

### Accessing

The `Optional` fields support all the usual combinators `Option` has and when needed they can be converted back with `.toOption`.
In many cases we expect that the optional fields are specified in the appication logic that works with the _Kubernetes_ clients. To support these there are _getter effects_ on each case class failing the ZIO effect in case the field is not present.

### Optics
Support for [Quicklens](quicklens.md) and [Monocle](monocle.md) is also available.
