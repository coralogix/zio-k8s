package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.{
  FieldSelector,
  K8sNamespace,
  LabelSelector,
  ListResourceVersion,
  PropagationPolicy,
  Reseted,
  TypedWatchEvent
}
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, Status }
import zio.{ IO, Schedule }
import zio.clock.Clock
import zio.duration.Duration
import zio.stream.{ Stream, ZStream }

trait Resource[T] {
  def getAll(
    namespace: Option[K8sNamespace],
    chunkSize: Int = 10,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None,
    resourceVersion: ListResourceVersion = ListResourceVersion.MostRecent
  ): Stream[K8sFailure, T]

  def watch(
    namespace: Option[K8sNamespace],
    resourceVersion: Option[String],
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): Stream[K8sFailure, TypedWatchEvent[T]]

  def watchForever(
    namespace: Option[K8sNamespace],
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): ZStream[Clock, K8sFailure, TypedWatchEvent[T]] =
    ZStream.succeed(Reseted) ++ watch(namespace, None, fieldSelector, labelSelector)
      .retry(Schedule.recurWhileEquals(Gone))

  def get(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T]

  def create(
    newResource: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false
  ): IO[K8sFailure, T]

  def replace(
    name: String,
    updatedResource: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false
  ): IO[K8sFailure, T]

  def delete(
    name: String,
    deleteOptions: DeleteOptions,
    namespace: Option[K8sNamespace],
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None
  ): IO[K8sFailure, Status]
}

trait NamespacedResource[T] {
  val asGenericResource: Resource[T]

  def getAll(
    namespace: Option[K8sNamespace],
    chunkSize: Int = 10,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None,
    resourceVersion: ListResourceVersion = ListResourceVersion.MostRecent
  ): Stream[K8sFailure, T]
  def watch(
    namespace: Option[K8sNamespace],
    resourceVersion: Option[String],
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): Stream[K8sFailure, TypedWatchEvent[T]]

  def watchForever(
    namespace: Option[K8sNamespace],
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): ZStream[Clock, K8sFailure, TypedWatchEvent[T]]

  def get(name: String, namespace: K8sNamespace): IO[K8sFailure, T]

  def create(newResource: T, namespace: K8sNamespace, dryRun: Boolean = false): IO[K8sFailure, T]

  def replace(
    name: String,
    updatedResource: T,
    namespace: K8sNamespace,
    dryRun: Boolean = false
  ): IO[K8sFailure, T]

  def delete(
    name: String,
    deleteOptions: DeleteOptions,
    namespace: K8sNamespace,
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None
  ): IO[K8sFailure, Status]
}

trait ClusterResource[T] {
  val asGenericResource: Resource[T]

  def getAll(
    chunkSize: Int = 10,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None,
    resourceVersion: ListResourceVersion = ListResourceVersion.MostRecent
  ): Stream[K8sFailure, T]

  def watch(
    resourceVersion: Option[String],
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): Stream[K8sFailure, TypedWatchEvent[T]]

  def watchForever(
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): ZStream[Clock, K8sFailure, TypedWatchEvent[T]]

  def get(name: String): IO[K8sFailure, T]

  def create(newResource: T, dryRun: Boolean = false): IO[K8sFailure, T]

  def replace(
    name: String,
    updatedResource: T,
    dryRun: Boolean = false
  ): IO[K8sFailure, T]

  def delete(
    name: String,
    deleteOptions: DeleteOptions,
    dryRun: Boolean = false,
    gracePeriod: Option[Duration] = None,
    propagationPolicy: Option[PropagationPolicy] = None
  ): IO[K8sFailure, Status]
}
