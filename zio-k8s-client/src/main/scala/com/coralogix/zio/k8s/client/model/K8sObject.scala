package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.{ K8sFailure, UndefinedField }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ ObjectMeta, OwnerReference }
import zio.{ IO, ZIO }

trait K8sObject[T] {
  def metadata(obj: T): Optional[ObjectMeta]
  def mapMetadata(f: ObjectMeta => ObjectMeta)(r: T): T

  def getName(obj: T): IO[K8sFailure, String] =
    ZIO.fromEither(metadata(obj).flatMap(_.name).toRight(UndefinedField("metadata.name")))

  def getUid(obj: T): IO[K8sFailure, String] =
    ZIO.fromEither(metadata(obj).flatMap(_.uid).toRight(UndefinedField("metadata.uid")))

  def generation(obj: T): Long = metadata(obj).flatMap(_.generation).getOrElse(0L)

  def attachOwner(obj: T)(
    ownerName: String,
    ownerUid: String,
    ownerType: K8sResourceType
  ): T =
    mapMetadata(metadata =>
      metadata.copy(ownerReferences =
        Some(
          metadata.ownerReferences.getOrElse(Vector.empty) :+
            OwnerReference(
              apiVersion = s"${ownerType.group}/${ownerType.version}".stripPrefix("/"),
              kind = ownerType.resourceType,
              name = ownerName,
              uid = ownerUid,
              controller = Some(true),
              blockOwnerDeletion = Some(true)
            )
        )
      )
    )(obj)

  def tryAttachOwner[OwnerT: K8sObject: ResourceMetadata](
    obj: T
  )(owner: OwnerT): IO[K8sFailure, T] = {
    import K8sObject._
    for {
      name <- owner.getName
      uid  <- owner.getUid
      typ   = implicitly[ResourceMetadata[OwnerT]].resourceType
    } yield attachOwner(obj)(name, uid, typ)
  }

  def isOwnedBy[OwnerT: K8sObject: ResourceMetadata](obj: T)(owner: OwnerT): Boolean = {
    import K8sObject._
    (for {
      name <- owner.metadata.flatMap(_.name)
      uid  <- owner.metadata.flatMap(_.uid)
      typ   = implicitly[ResourceMetadata[OwnerT]].resourceType
      refs <- metadata(obj).flatMap(_.ownerReferences)
      found = refs.exists(ownerReference =>
                ownerReference.kind == typ.resourceType &&
                  ownerReference.name == name &&
                  ownerReference.uid == uid
              )
    } yield found).getOrElse(false)
  }
}

object K8sObject {
  implicit class Ops[T](protected val obj: T)(implicit protected val impl: K8sObject[T])
      extends K8sObjectOps[T]
}
