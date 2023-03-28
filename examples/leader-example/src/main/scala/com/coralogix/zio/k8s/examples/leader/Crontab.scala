package com.coralogix.zio.k8s.examples.leader

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

// Example of defining a custom resource client without using zio-k8s-crd

case class Crontab(
  metadata: Optional[ObjectMeta],
  status: Optional[CrontabStatus],
  spec: String,
  image: String,
  replicas: Int
)

object Crontab {
  implicit val crontabCodec: Codec[Crontab] = deriveCodec

  implicit val metadata: ResourceMetadata[Crontab] = new ResourceMetadata[Crontab] {
    override def kind: String = "CronTab"
    override def apiVersion: String = "apiextensions.k8s.io/v1"
    override def resourceType: model.K8sResourceType =
      K8sResourceType("crontabs", "apiextensions.k8s.io", "v1")
  }

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
}

case class CrontabStatus(replicas: Int, labelSelector: String)

object CrontabStatus {
  implicit val crontabStatusCodec: Codec[CrontabStatus] = deriveCodec
}

package object crontabs {
  type Crontabs = Has[Crontabs.Service]

  object Crontabs {
    type Generic = Has[NamespacedResource[Crontab]]
      with Has[NamespacedResourceStatus[CrontabStatus, Crontab]]

    trait Service
        extends NamespacedResource[Crontab] with NamespacedResourceStatus[CrontabStatus, Crontab] {
      val asGeneric: Generic = Has[NamespacedResource[Crontab]](this) ++ Has[
        NamespacedResourceStatus[CrontabStatus, Crontab]
      ](this)
    }

    class Live(
      override val asGenericResource: ResourceClient[Crontab, Status],
      override val asGenericResourceStatus: ResourceStatusClient[CrontabStatus, Crontab]
    ) extends Service

    val live: ZLayer[Has[K8sCluster]
      with Has[
        SttpBackend[Task, ZioStreams with WebSockets]
      ], Nothing, Crontabs] =
      ZLayer.fromServices[SttpBackend[Task, ZioStreams with WebSockets], K8sCluster, Service] {
        (backend: SttpBackend[Task, ZioStreams with WebSockets], cluster: K8sCluster) =>
          val client =
            new ResourceClient[Crontab, Status](Crontab.metadata.resourceType, cluster, backend)
          val statusClient = new ResourceStatusClient[CrontabStatus, Crontab](
            Crontab.metadata.resourceType,
            cluster,
            backend
          )
          new Live(client, statusClient)
      }
  }
}
