package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.K8sNamespace
import zio.IO

trait Subresource[T] {
  def get(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T]
  def replace(
    name: String,
    updatedValue: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T]
  def create(value: T, namespace: Option[K8sNamespace], dryRun: Boolean): IO[K8sFailure, T]
}
