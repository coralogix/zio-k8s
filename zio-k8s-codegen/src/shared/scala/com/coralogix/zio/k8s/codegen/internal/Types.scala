package com.coralogix.zio.k8s.codegen.internal

import io.github.vigoo.metagen.core._

object Types {
  def zio(r: ScalaType, e: ScalaType, a: ScalaType): ScalaType =
    ScalaType(Packages.zio, "ZIO", r, e, a)

  val zio_ : ScalaType = ScalaType(Packages.zio, "ZIO")

  def zlayer(r: ScalaType, e: ScalaType, a: ScalaType): ScalaType =
    ScalaType(Packages.zio, "ZLayer", r, e, a)

  val zlayer_ : ScalaType = ScalaType(Packages.zio, "ZLayer")

  def zstream(r: ScalaType, e: ScalaType, a: ScalaType): ScalaType =
    ScalaType(Packages.zio / "stream", "ZStream", r, e, a)

  val zstream_ : ScalaType = ScalaType(Packages.zio / "stream", "ZStream")

  def k8sIO(a: ScalaType): ScalaType =
    ScalaType(Packages.zio, "IO", k8sFailure, a)

  def chunk(a: ScalaType): ScalaType =
    ScalaType(Packages.zio, "Chunk", a)

  val chunk_ : ScalaType = ScalaType(Packages.zio, "Chunk")

  val duration: ScalaType = ScalaType(Packages.zio / "duration", "Duration")

  def has(a: ScalaType): ScalaType = ScalaType(Packages.zio, "Has", a)

  val status: ScalaType = ScalaType(
    Package("com", "coralogix", "zio", "k8s", "model", "pkg", "apis", "meta", "v1"),
    "Status"
  )
  val objectMeta: ScalaType = ScalaType(
    Package("com", "coralogix", "zio", "k8s", "model", "pkg", "apis", "meta", "v1"),
    "ObjectMeta"
  )
  val deleteOptions: ScalaType = ScalaType(
    Package("com", "coralogix", "zio", "k8s", "model", "pkg", "apis", "meta", "v1"),
    "DeleteOptions"
  )

  val k8sFailure: ScalaType = ScalaType(Packages.k8sClient, "K8sFailure")
  val undefinedField: ScalaType = ScalaType(Packages.k8sClient, "UndefinedField")

  def optional(t: ScalaType): ScalaType =
    ScalaType(Packages.zioPrelude / "data", "Optional", t)

  val optionalAbsent: ScalaType =
    ScalaType(Packages.zioPrelude / "data" / "Optional", "Absent")
  val optionalPresent: ScalaType =
    ScalaType(Packages.zioPrelude / "data" / "Optional", "Present")

  def resourceMetadata(a: ScalaType): ScalaType =
    ScalaType(Packages.k8sClientModel, "ResourceMetadata", a)

  val resourceMetadata_ : ScalaType = ScalaType(Packages.k8sClientModel, "ResourceMetadata")

  val k8sResourceType: ScalaType =
    ScalaType(Packages.k8sClientModel, "K8sResourceType")

  val k8sCluster: ScalaType = ScalaType(Packages.k8sClientModel, "K8sCluster")
  val k8sNamespace: ScalaType = ScalaType(Packages.k8sClientModel, "K8sNamespace")
  val propagationPolicy: ScalaType = ScalaType(Packages.k8sClientModel, "PropagationPolicy")
  val fieldSelector: ScalaType = ScalaType(Packages.k8sClientModel, "FieldSelector")
  val labelSelector: ScalaType = ScalaType(Packages.k8sClientModel, "LabelSelector")
  val listResourceVersion: ScalaType = ScalaType(Packages.k8sClientModel, "ListResourceVersion")
  def typedWatchEvent(a: ScalaType): ScalaType =
    ScalaType(Packages.k8sClientModel, "TypedWatchEvent", a)

  def resource(a: ScalaType): ScalaType = ScalaType(Packages.k8sClient, "Resource", a)
  def namespacedResource(a: ScalaType): ScalaType =
    ScalaType(Packages.k8sClient, "NamespacedResource", a)
  def clusterResource(a: ScalaType): ScalaType = ScalaType(Packages.k8sClient, "ClusterResource", a)
  def resourceClient(a: ScalaType, b: ScalaType) =
    ScalaType(Packages.k8sClientImpl, "ResourceClient", a, b)
  def testResourceClient: ScalaType =
    ScalaType(Packages.k8sClientTest, "TestResourceClient")

  def resourceStatus(a: ScalaType, b: ScalaType): ScalaType =
    ScalaType(Packages.k8sClient, "ResourceStatus", a, b)
  def namespacedResourceStatus(a: ScalaType, b: ScalaType): ScalaType =
    ScalaType(Packages.k8sClient, "NamespacedResourceStatus", a, b)
  def clusterResourceStatus(a: ScalaType, b: ScalaType): ScalaType =
    ScalaType(Packages.k8sClient, "ClusterResourceStatus", a, b)
  def resourceStatusClient(a: ScalaType, b: ScalaType): ScalaType =
    ScalaType(Packages.k8sClientImpl, "ResourceStatusClient", a, b)
  def testResourceStatusClient: ScalaType =
    ScalaType(Packages.k8sClientTest, "TestResourceStatusClient")

  def subresource(a: ScalaType): ScalaType =
    ScalaType(Packages.k8sClient, "Subresource", a)
  def subresourceClient(a: ScalaType): ScalaType =
    ScalaType(Packages.k8sClientImpl, "SubresourceClient", a)
  def testSubresourceClient: ScalaType =
    ScalaType(Packages.k8sClientTest, "TestSubresourceClient")

  def resourceDelete(a: ScalaType, b: ScalaType): ScalaType =
    ScalaType(Packages.k8sClient, "ResourceDelete", a, b)
  def resourceDeleteAll(a: ScalaType): ScalaType =
    ScalaType(Packages.k8sClient, "ResourceDeleteAll", a)
  def namespacedResourceDelete(a: ScalaType, b: ScalaType): ScalaType =
    ScalaType(Packages.k8sClient, "NamespacedResourceDelete", a, b)
  def namespacedResourceDeleteAll(a: ScalaType): ScalaType =
    ScalaType(Packages.k8sClient, "NamespacedResourceDeleteAll", a)
  def clusterResourceDelete(a: ScalaType, b: ScalaType): ScalaType =
    ScalaType(Packages.k8sClient, "ClusterResourceDelete", a, b)
  def clusterResourceDeleteAll(a: ScalaType): ScalaType =
    ScalaType(Packages.k8sClient, "ClusterResourceDeleteAll", a)

  val json: ScalaType = ScalaType(Packages.circe, "Json")
  def jsonEncoder(a: ScalaType): ScalaType = ScalaType(Packages.circe, "Encoder", a)
  def jsonDecoder(a: ScalaType): ScalaType = ScalaType(Packages.circe, "Decoder", a)

  def monocleLens(s: ScalaType, a: ScalaType): ScalaType =
    ScalaType(Packages.monocle, "Lens", s, a)

  val monocleGenLens: ScalaType = ScalaType(Packages.monocle / "macros", "GenLens")

  def monocleOptional(s: ScalaType, a: ScalaType): ScalaType =
    ScalaType(Packages.monocle, "Optional", s, a)
}