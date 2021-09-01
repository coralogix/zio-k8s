package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.K8sResourceType

/** K8s API request information to be provided in [[K8sFailure]] failures
  * @param resourceType
  *   Resource type
  * @param operation
  *   Operation name
  */
case class K8sRequestInfo(resourceType: K8sResourceType, operation: String)
