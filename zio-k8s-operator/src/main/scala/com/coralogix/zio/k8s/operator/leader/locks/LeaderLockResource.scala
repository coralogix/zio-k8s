package com.coralogix.zio.k8s.operator.leader.locks

import com.coralogix.zio.k8s.client.config.backend.K8sBackend
import com.coralogix.zio.k8s.client.impl.ResourceClient
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.client.{
  model,
  NamespacedResource,
  NamespacedResourceDelete,
  ResourceDelete
}
import com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ ObjectMeta, Status }
import io.circe._
import io.circe.syntax._
import zio._
import zio.prelude.data.Optional
import zio.stream.{ ZPipeline, ZStream }

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

  val customResourceDefinition: ZIO[
    Any,
    Throwable,
    com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition
  ] =
    for {
      rawYaml <- ZStream
                   .fromInputStream(getClass.getResourceAsStream("/crds/leaderlock.yaml"))
                   .via(ZPipeline.utf8Decode)
                   .runFold("")(_ ++ _)
                   .orDie
      crd     <- ZIO.fromEither(
                   _root_.io.circe.yaml.parser.parse(rawYaml).flatMap(_.as[CustomResourceDefinition])
                 )
    } yield crd
}

package object leaderlockresources {
  type LeaderLockResources = LeaderLockResources.Service

  object LeaderLockResources {
    type Generic = NamespacedResource[LeaderLockResource]

    trait Service
        extends NamespacedResource[LeaderLockResource]
        with NamespacedResourceDelete[LeaderLockResource, Status]

    class Live(
      override val asGenericResource: ResourceClient[LeaderLockResource, Status]
    ) extends Service {
      val asGenericResourceDelete: ResourceDelete[LeaderLockResource, Status] = asGenericResource
    }

    val live: ZLayer[
      K8sCluster with K8sBackend,
      Nothing,
      LeaderLockResources
    ] = ZLayer {
      for {
        backend <- ZIO.service[K8sBackend]
        cluster <- ZIO.service[K8sCluster]
      } yield {
        val client = new ResourceClient[LeaderLockResource, Status](
          LeaderLockResource.metadata.resourceType,
          cluster,
          backend
        )
        new Live(client)
      }
    }
  }
}
