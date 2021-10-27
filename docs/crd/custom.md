---
id: crd_custom
title:  "Custom Resource Definition by hand"
---

This page explains how to define manually the data model and client module for _custom resources_, without relying 
on the `zio-k8s-crd` code generator plugin.

```scala mdoc:invisible
import com.coralogix.zio.k8s.client.impl.{ ResourceClient, ResourceStatusClient }
import com.coralogix.zio.k8s.client.model.{
  K8sCluster,
  K8sObject,
  K8sObjectStatus,
  K8sResourceType,
  Optional,
  ResourceMetadata
}
import com.coralogix.zio.k8s.client._
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ ObjectMeta, Status }
import io.circe.Codec
import io.circe.generic.semiauto._
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import zio.{ Has, Task, ZLayer }
```

We are going to use the [Crontab example](https://kubernetes.io/docs/tasks/extend-kubernetes/custom-resources/custom-resource-definitions/) 
from the Kubernetes documentation.

First we define the model for our `Crontab` resource. This should be a _case class_ and it can use the zio-k8s specific `Optional` type
for optional fields instead of standard `Option` to reduce boilerplate when defining resources from code. It should have a standard
`metadata` field, and optionally a `status` field:

```scala mdoc
case class Crontab(
  metadata: Optional[ObjectMeta],
  status: Optional[CrontabStatus],
  spec: String,
  image: String,
  replicas: Int
)

case class CrontabStatus(replicas: Int, labelSelector: String)
```

There is a couple of type classes that has to be implemented for these data structures. First of all, it has
to support being encoded to and decoded from JSON. This can be done by providing a _JSON codec_:

```scala mdoc
implicit val crontabCodec: Codec[Crontab] = deriveCodec
implicit val crontabStatusCodec: Codec[CrontabStatus] = deriveCodec
```

Note that in practice you should put each implicit in the related type's companion object.

In order to be able to use the generic functions in zio-k8s, you have to provide an instance of 
the `K8sObject` and `K8sObjectStatus` type classes, defining how to get and manipulate the metadata
and status fields of the custom resource:

```scala mdoc
implicit val k8sObject: K8sObject[Crontab] =
  new K8sObject[Crontab] {
    override def metadata(obj: Crontab): Optional[ObjectMeta] = obj.metadata
    override def mapMetadata(f: ObjectMeta => ObjectMeta)(r: Crontab): Crontab =
      r.copy(metadata = r.metadata.map(f))
  }

  implicit val k8sObjectStatus: K8sObjectStatus[Crontab, CrontabStatus] =
    new K8sObjectStatus[Crontab, CrontabStatus] {
      override def status(obj: Crontab): Optional[CrontabStatus] = obj.status
      override def mapStatus(f: CrontabStatus => CrontabStatus)(obj: Crontab): Crontab =
        obj.copy(status = obj.status.map(f))
    }
```

Finally we need to provide some _metadata_ about the resource type with the `ResourceMetadata` type class:

```scala mdoc
implicit val metadata: ResourceMetadata[Crontab] = new ResourceMetadata[Crontab] {
  override def kind: String = "CronTab"
  override def apiVersion: String = "apiextensions.k8s.io/v1"
  override def resourceType: model.K8sResourceType = K8sResourceType("crontabs", "apiextensions.k8s.io", "v1")
}

```

This is all we need for the _model_ of our custom resources. The next step is creating a ZIO module for working
with these resources through the Kubernetes API.

The following code snippet shows the recommended way to define these modules (`crontabs` can usually defined
as a _package object_ instead) :

```scala mdoc
object crontabs {
  type Crontabs = Has[Crontabs.Service]

  object Crontabs {
    // Generic representation (optional) 
    type Generic =
      Has[NamespacedResource[Crontab]] with 
      Has[NamespacedResourceStatus[CrontabStatus, Crontab]]

    trait Service 
      extends NamespacedResource[Crontab]
      with NamespacedResourceStatus[CrontabStatus, Crontab] {
      
      // Convert to generic representation (optional)
      val asGeneric: Generic = 
        Has[NamespacedResource[Crontab]](this) ++ 
        Has[NamespacedResourceStatus[CrontabStatus, Crontab]](this)
    }

    final class Live(
      override val asGenericResource: ResourceClient[Crontab, Status],
      override val asGenericResourceStatus: ResourceStatusClient[CrontabStatus, Crontab]
    ) extends Service

    val live
      : ZLayer[Has[K8sCluster] with Has[SttpBackend[Task, ZioStreams with WebSockets]], Nothing, Crontabs] =
      ZLayer.fromServices[SttpBackend[Task, ZioStreams with WebSockets], K8sCluster, Service] {
        (backend: SttpBackend[Task, ZioStreams with WebSockets], cluster: K8sCluster) =>
          val client = new ResourceClient[Crontab, Status](metadata.resourceType, cluster, backend)
          val statusClient = new ResourceStatusClient[CrontabStatus, Crontab](
            metadata.resourceType,
            cluster,
            backend
          )
          new Live(client, statusClient)
      }
  }
}
```

In addition to this you can add _accessor functions_ to the `crontabs` package in the style of

```scala
def get(name: String, namespace: K8sNamespace): ZIO[Crontabs, K8sFailure, Crontab] =
  ZIO.accessM(_.get.get(name, namespace))
```
