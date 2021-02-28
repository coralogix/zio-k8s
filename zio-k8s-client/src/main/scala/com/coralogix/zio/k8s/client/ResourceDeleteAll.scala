package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.{
  FieldSelector,
  K8sNamespace,
  LabelSelector,
  PropagationPolicy
}
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, Status }
import zio.IO
import zio.duration.Duration

trait ResourceDeleteAll[T] {
  def deleteAll(
    deleteOptions: DeleteOptions,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): IO[K8sFailure, Status]
}

trait NamespacedResourceDeleteAll[T] {
  val asGenericResourceDeleteAll: ResourceDeleteAll[T]

  def deleteAll(
    deleteOptions: DeleteOptions,
    namespace: K8sNamespace,
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): IO[K8sFailure, Status]
}

trait ClusterResourceDeleteAll[T] {
  val asGenericResourceDeleteAll: ResourceDeleteAll[T]

  def deleteAll(
    deleteOptions: DeleteOptions,
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): IO[K8sFailure, Status]
}
