package com.coralogix.zio.k8s.client

import io.circe.{ Codec, Decoder, Encoder, HCursor }
import sttp.client3.IsOption.True
import sttp.client3.{ Empty, IsOption, RequestT, UriContext }
import sttp.model._
import zio.Chunk

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import scala.util.Try
import zio._
import zio.prelude.data.Optional

package object model extends LabelSelector.Syntax with FieldSelector.Syntax {

  /** Data type describing a configured Kuberntes cluster
    * @param host
    *   Host to connect to
    * @param applyToken
    *   Function to apply an authentication token to the HTTP request
    */
  case class K8sCluster(
    host: Uri,
    applyToken: Option[
      RequestT[Empty, Either[String, String], Any] => RequestT[Empty, Either[String, String], Any]
    ]
  )

  /** Metadata identifying a Kubernetes resource
    * @param resourceType
    *   Resource type (kind)
    * @param group
    *   Group
    * @param version
    *   Version
    */
  case class K8sResourceType(resourceType: String, group: String, version: String)

  /** A Kubernetes namespace
    */
  case class K8sNamespace(value: String) extends AnyVal

  object K8sNamespace {
    val default: K8sNamespace = K8sNamespace("default")
  }

  /** Kubernetes API URI
    */
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
    continueToken: Option[String],
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None,
    resourceVersion: ListResourceVersion = ListResourceVersion.MostRecent
  ) extends K8sUri {
    override def toUri(cluster: K8sCluster): Uri =
      K8sSimpleUri(resource, None, None, namespace)
        .toUri(cluster)
        .addParam("limit", limit.toString)
        .addParam("continue", continueToken)
        .addParam("fieldSelector", fieldSelector.map(_.asQuery))
        .addParam("labelSelector", labelSelector.map(_.asQuery))
        .addParam("resourceVersion", resourceVersion.resourceVersion)
        .addParam("resourceVersionMatch", resourceVersion.resourceVersionMatch)
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

  final case class K8sDeletingUri(
    resource: K8sResourceType,
    name: String,
    subresource: Option[String],
    namespace: Option[K8sNamespace],
    dryRun: Boolean,
    gracePeriod: Option[Duration],
    propagationPolicy: Option[PropagationPolicy]
  ) extends K8sUri {
    override def toUri(cluster: K8sCluster): Uri =
      K8sSimpleUri(resource, Some(name), subresource, namespace)
        .toUri(cluster)
        .addParam("dryRun", if (dryRun) Some("All") else None)
        .addParam("gracePeriodSeconds", gracePeriod.map(_.toSeconds.toString))
        .addParam(
          "propagationPolicy",
          propagationPolicy.map {
            case PropagationPolicy.Orphan     => "Orphan"
            case PropagationPolicy.Background => "Background"
            case PropagationPolicy.Foreground => "Foreground"
          }
        )
  }

  final case class K8sDeletingManyUri(
    resource: K8sResourceType,
    namespace: Option[K8sNamespace],
    dryRun: Boolean,
    gracePeriod: Option[Duration],
    propagationPolicy: Option[PropagationPolicy],
    fieldSelector: Option[FieldSelector],
    labelSelector: Option[LabelSelector]
  ) extends K8sUri {
    override def toUri(cluster: K8sCluster): Uri =
      K8sSimpleUri(resource, None, None, namespace)
        .toUri(cluster)
        .addParam("dryRun", if (dryRun) Some("All") else None)
        .addParam("gracePeriodSeconds", gracePeriod.map(_.toSeconds.toString))
        .addParam(
          "propagationPolicy",
          propagationPolicy.map {
            case PropagationPolicy.Orphan     => "Orphan"
            case PropagationPolicy.Background => "Background"
            case PropagationPolicy.Foreground => "Foreground"
          }
        )
        .addParam("fieldSelector", fieldSelector.map(_.asQuery))
        .addParam("labelSelector", labelSelector.map(_.asQuery))
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
    resourceVersion: Option[String],
    allowBookmarks: Boolean,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ) extends K8sUri {
    override def toUri(cluster: K8sCluster): Uri =
      K8sSimpleUri(resource, None, None, namespace)
        .toUri(cluster)
        .addParam("watch", "1")
        .addParam("resourceVersion", resourceVersion)
        .addParam("fieldSelector", fieldSelector.map(_.asQuery))
        .addParam("labelSelector", labelSelector.map(_.asQuery))
        .addParam("allowWatchBookmarks", if (allowBookmarks) Some("true") else None)
  }

  val k8sDateTimeFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
    .withZone(ZoneOffset.UTC)

  implicit def optionalEncoder[A: Encoder]: Encoder[Optional[A]] =
    Encoder.encodeOption[A].contramap(_.toOption)
  implicit def optionalDecoder[A: Decoder]: Decoder[Optional[A]] =
    Decoder.decodeOption[A].map(Optional.OptionIsNullable)

  private object IsOptionTrue extends IsOption[Any] {
    override def isOption: Boolean = true
  }
  implicit def optionalIsOption[T]: IsOption[Optional[T]] = IsOptionTrue
  implicit def leftOptionalIsOption[T]: IsOption[Either[Optional[T], _]] = IsOptionTrue
  implicit def rightOptionalIsOption[T]: IsOption[Either[_, Optional[T]]] = IsOptionTrue
}
