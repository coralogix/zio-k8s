package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.{ K8sFailure, UndefinedField }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import zio.{ IO, ZIO }

trait K8sObject[T] {
  def metadata(obj: T): Option[ObjectMeta]
  def mapMetadata(f: ObjectMeta => ObjectMeta)(r: T): T

  def getName(obj: T): IO[K8sFailure, String] =
    ZIO.fromEither(metadata(obj).flatMap(_.name).toRight(UndefinedField("metadata.name")))

  def getUid(obj: T): IO[K8sFailure, String] =
    ZIO.fromEither(metadata(obj).flatMap(_.uid).toRight(UndefinedField("metadata.uid")))

  def generation(obj: T): Long = metadata(obj).flatMap(_.generation).getOrElse(0L)
}

object K8sObject {
  implicit class Ops[T](protected val obj: T)(implicit protected val impl: K8sObject[T])
      extends K8sObjectOps[T]
}
