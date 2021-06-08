---
id: overview_generic
title: "Generic code"
---

While having a specific ZIO module and strictly typed interface for each available _Kubernetes resource_, sometimes we want to write more generic functions:

- that work on any given resource (maybe regardless of being namespaced or cluster level)
- that work on a given capability for example on any resource having a _scale subresource_

This section shows the available tools for implementing such functions.

## Generic layers
Every resource client module defines an _alternative_ type alias called `Generic`.

For example the default `Pods` type is defined as the following:

```scala
type Pods = Has[Pods.Service]
```

and it's generic version, `Pods.Generic` defined in the companion object of `Pods` as:

```scala
type Generic = 
    Has[NamespacedResource[Pod]]
        with Has[NamespacedResourceStatus[PodStatus, Pod]]
        with Has[NamespacedLogSubresource[Pod]] 
        with Has[NamespacedEvictionSubresource[Pod]]
        with Has[NamespacedBindingSubresource[Pod]]
```

The `pods` layer can be converted to its `Generic` representation with `.asGeneric`:

```scala mdoc:invisible
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import sttp.client3._
import sttp.model._
import zio._
import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.system.System
```

```scala mdoc:silent
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.client.K8sFailure

val pods: ZLayer[Blocking with System, Throwable, Pods] = k8sDefault >>> Pods.live
val generic: ZLayer[Blocking with System, Throwable, Pods.Generic] = pods.map(_.get.asGeneric)
```

This `generic` layer can be provided to generic functions that work with any kind of resource:

```scala mdoc:silent
import com.coralogix.zio.k8s.client._
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.impl.ResourceClient
import zio._
import zio.console.Console

def getAndPrint[T : Tag](name: String, namespace: K8sNamespace): ZIO[Console with Has[NamespacedResource[T]], K8sFailure, Unit] =
    for {
        obj <- ResourceClient.namespaced.get(name, namespace)
        _ <- console.putStrLn(obj.toString).ignore
    } yield ()
```

## K8sObject
Not knowing anything about the resource type in these generic functions is very limiting.
There are a couple of _type classes_ available implemented by the _Kubernetes model classes_ that can be used in these scenarios.

### K8sObject
The `K8sObject` type class provides access to the object's metadata, that always have the type `ObjectMeta`, and some other
helper functions to get parts of this metadata and to manipulate _ownership_.

For example the above example can be extended to print the object's UID instead:

```scala mdoc:silent
import com.coralogix.zio.k8s.client.model.K8sObject
import com.coralogix.zio.k8s.client.model.K8sObject._

def getAndPrintUid[T : Tag : K8sObject](name: String, namespace: K8sNamespace): ZIO[Console with Has[NamespacedResource[T]], K8sFailure, Unit] =
    for {
        obj <- ResourceClient.namespaced.get(name, namespace)
        uid <- obj.getUid
        _ <- console.putStrLn(uid).ignore
    } yield ()
```

### K8sObjectStatus
Similarily to `K8sObject`, the `K8sObjectStatus` type class provides access to the resouce's _status subresource_. It is parametrized by both the resouce type and the status type:

```scala
trait K8sObjectStatus[ResourceT, StatusT]
```

### Resource metadata
Finally each resource model class has an implicit `ResourceMetadata` value as well. This can be used to get information about
the underlying resource's _group/version/kind_.
