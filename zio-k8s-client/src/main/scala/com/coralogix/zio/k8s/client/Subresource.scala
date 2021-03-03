package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.K8sNamespace
import zio.IO
import zio.stream.{ ZStream, ZTransducer }

trait Subresource[T] {
  def get(
    name: String,
    namespace: Option[K8sNamespace],
    customParameters: Map[String, String] = Map.empty
  ): IO[K8sFailure, T]

  def streamingGet(
    name: String,
    namespace: Option[K8sNamespace],
    transducer: ZTransducer[Any, K8sFailure, Byte, T],
    customParameters: Map[String, String] = Map.empty
  ): ZStream[Any, K8sFailure, T]

  def replace(
    name: String,
    updatedValue: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T]

  def create(
    name: String,
    value: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T]
}
