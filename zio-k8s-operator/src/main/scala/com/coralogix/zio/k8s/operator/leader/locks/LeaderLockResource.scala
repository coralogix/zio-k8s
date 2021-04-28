package com.coralogix.zio.k8s.operator.leader.locks

import com.coralogix.zio.k8s.client.impl.ResourceClient
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.client.{ model, NamespacedResource }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import io.circe._
import io.circe.syntax._
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import zio.{ Has, Task, ZLayer }

case class LeaderLockResource(metadata: Optional[ObjectMeta])

object LeaderLockResource {
  implicit val PodEncoder: Encoder[LeaderLockResource] = (value: LeaderLockResource) =>
    Json.obj(
      "kind"       := "LeaderLock",
      "apiVersion" := "coralogix.com/v1",
      "metadata"   := value.metadata
    )
  implicit val LeaderLockResourceDecoder: Decoder[LeaderLockResource] =
    Decoder.forProduct1("metadata")(LeaderLockResource.apply)

  implicit val metadata: ResourceMetadata[LeaderLockResource] =
    new ResourceMetadata[LeaderLockResource] {
      override def kind: String = "LeaderLock"
      override def apiVersion: String = "coralogix.com/v1"
      override def resourceType: model.K8sResourceType =
        K8sResourceType("leaderlocks", "coralogix.com", "v1")
    }

  implicit val k8sObject: K8sObject[LeaderLockResource] =
    new K8sObject[LeaderLockResource] {
      override def metadata(obj: LeaderLockResource): Optional[ObjectMeta] = obj.metadata

      override def mapMetadata(f: ObjectMeta => ObjectMeta)(
        r: LeaderLockResource
      ): LeaderLockResource =
        r.copy(metadata = r.metadata.map(f))
    }
}

package object leaderlockresources {
  type LeaderLockResources = Has[LeaderLockResources.Service]

  object LeaderLockResources {
    type Generic = Has[NamespacedResource[LeaderLockResource]]

    trait Service extends NamespacedResource[LeaderLockResource] {
      val asGeneric: Generic = Has[NamespacedResource[LeaderLockResource]](this)
    }

    class Live(
      override val asGenericResource: ResourceClient[LeaderLockResource]
    ) extends Service

    val live: ZLayer[Has[K8sCluster] with Has[
      SttpBackend[Task, ZioStreams with WebSockets]
    ], Nothing, LeaderLockResources] =
      ZLayer.fromServices[SttpBackend[Task, ZioStreams with WebSockets], K8sCluster, Service] {
        (backend: SttpBackend[Task, ZioStreams with WebSockets], cluster: K8sCluster) =>
          val client = new ResourceClient[LeaderLockResource](
            LeaderLockResource.metadata.resourceType,
            cluster,
            backend
          )
          new Live(client)
      }
  }
}
