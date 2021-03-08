package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.model.K8sObject._

/** Watch events with decoded payload
  * @tparam T Watched resource type
  */
sealed trait TypedWatchEvent[+T] {

  /** Resource version of the payload
    */
  val resourceVersion: Option[String]

  /** Namespace of the payload
    */
  val namespace: Option[K8sNamespace]
}

/** Watch stream reseted
  */
case object Reseted extends TypedWatchEvent[Nothing] {
  override val resourceVersion: Option[String] = None
  override val namespace: Option[K8sNamespace] = None
}

/** Resource added
  * @param item new object that has been added
  * @tparam T Watched resource type
  */
final case class Added[T: K8sObject](item: T) extends TypedWatchEvent[T] {
  override val resourceVersion: Option[String] = item.metadata.flatMap(_.resourceVersion).toOption
  override val namespace: Option[K8sNamespace] =
    item.metadata.flatMap(_.namespace).map(K8sNamespace.apply).toOption
}

/** Existing resource modified
  * @param item the modified object
  * @tparam T Watched resource type
  */
final case class Modified[T: K8sObject](item: T) extends TypedWatchEvent[T] {
  override val resourceVersion: Option[String] = item.metadata.flatMap(_.resourceVersion).toOption
  override val namespace: Option[K8sNamespace] =
    item.metadata.flatMap(_.namespace).map(K8sNamespace.apply).toOption
}

/** Resource has been deleted
  * @param item the deleted object
  * @tparam T Watched resource type
  */
final case class Deleted[T: K8sObject](item: T) extends TypedWatchEvent[T] {
  override val resourceVersion: Option[String] = item.metadata.flatMap(_.resourceVersion).toOption
  override val namespace: Option[K8sNamespace] =
    item.metadata.flatMap(_.namespace).map(K8sNamespace.apply).toOption
}
