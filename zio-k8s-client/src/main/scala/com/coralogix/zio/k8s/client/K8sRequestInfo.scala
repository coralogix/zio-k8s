package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.{
  FieldSelector,
  K8sNamespace,
  K8sResourceType,
  LabelSelector
}

object K8sRequestInfo {
  def apply(resourceType: K8sResourceType, operation: String): K8sRequestInfo =
    K8sRequestInfo(resourceType, operation, None, None, None, None)

  def apply(
    resourceType: K8sResourceType,
    operation: String,
    namespace: Option[K8sNamespace]
  ): K8sRequestInfo =
    K8sRequestInfo(resourceType, operation, namespace, None, None, None)
}

/** K8s API request information to be provided in [[K8sFailure]] failures
  * @param resourceType
  *   Resource type
  * @param operation
  *   Operation name
  */
case class K8sRequestInfo(
  resourceType: K8sResourceType,
  operation: String,
  namespace: Option[K8sNamespace],
  fieldSelector: Option[FieldSelector],
  labelSelector: Option[LabelSelector],
  name: Option[String]
)
