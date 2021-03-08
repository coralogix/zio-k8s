package com.coralogix.zio.k8s.client.model

/** Resource metadata typeclass
  * @tparam T Resource type
  */
trait ResourceMetadata[T] {

  /** Kubernetes kind
    */
  def kind: String

  /** Kubernetes API version string (composed group/version)
    */
  def apiVersion: String

  /** Kubernetes resource type metadata
    */
  def resourceType: K8sResourceType
}
