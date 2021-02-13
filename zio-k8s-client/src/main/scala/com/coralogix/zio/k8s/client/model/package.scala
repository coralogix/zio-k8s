package com.coralogix.zio.k8s.client

import sttp.client3.UriContext
import sttp.model._

package object model {

  case class K8sCluster(host: Uri, token: String)

  case class K8sResourceType(resourceType: String, group: String, version: String)

  case class K8sNamespace(value: String) extends AnyVal

  object K8sNamespace {
    val default: K8sNamespace = K8sNamespace("default")
  }

  sealed trait K8sUri {
    def toUri(cluster: K8sCluster): Uri
  }

  final case class K8sSimpleUri(
    resource: K8sResourceType,
    name: Option[String],
    subresource: Option[String],
    namespace: Option[K8sNamespace]
  ) extends K8sUri {
    override def toUri(cluster: K8sCluster): Uri = {
      val apiRoot = if (resource.group.nonEmpty) Seq("apis", resource.group) else Seq("api")
      ((name, namespace) match {
        case (Some(n), Some(ns)) =>
          uri"${cluster.host}/$apiRoot/${resource.version}/namespaces/${ns.value}/${resource.resourceType}/$n"
        case (None, Some(ns))    =>
          uri"${cluster.host}/$apiRoot/${resource.version}/namespaces/${ns.value}/${resource.resourceType}"
        case (Some(n), None)     =>
          uri"${cluster.host}/$apiRoot/${resource.version}/${resource.resourceType}/$n"
        case (None, None)        =>
          uri"${cluster.host}/$apiRoot/${resource.version}/${resource.resourceType}"
      }).addPath(subresource.toSeq)
    }
  }

  final case class K8sPaginatedUri(
    resource: K8sResourceType,
    namespace: Option[K8sNamespace],
    limit: Int,
    continueToken: Option[String]
  ) extends K8sUri {
    override def toUri(cluster: K8sCluster): Uri =
      K8sSimpleUri(resource, None, None, namespace)
        .toUri(cluster)
        .addParam("limit", limit.toString)
        .addParam("continue", continueToken)
  }

  final case class K8sModifierUri(
    resource: K8sResourceType,
    name: String,
    subresource: Option[String],
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ) extends K8sUri {
    override def toUri(cluster: K8sCluster): Uri =
      K8sSimpleUri(resource, Some(name), subresource, namespace)
        .toUri(cluster)
        .withParam("dryRun", if (dryRun) Some("All") else None)

  }

  final case class K8sCreatorUri(
    resource: K8sResourceType,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ) extends K8sUri {
    override def toUri(cluster: K8sCluster): Uri = {
      val apiRoot = if (resource.group.nonEmpty) Seq("apis", resource.group) else Seq("api")
      (namespace match {
        case Some(ns) =>
          uri"${cluster.host}/$apiRoot/${resource.version}/namespaces/${ns.value}/${resource.resourceType}"
        case None     =>
          uri"${cluster.host}/$apiRoot/${resource.version}/${resource.resourceType}"
      }).withParam("dryRun", if (dryRun) Some("All") else None)
    }
  }

  final case class K8sWatchUri(
    resource: K8sResourceType,
    namespace: Option[K8sNamespace],
    resourceVersion: Option[String]
  ) extends K8sUri {
    override def toUri(cluster: K8sCluster): Uri =
      K8sSimpleUri(resource, None, None, namespace)
        .toUri(cluster)
        .addParam("watch", "1")
        .addParam("resourceVersion", resourceVersion)
  }
}
