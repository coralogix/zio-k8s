package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.{ K8sFailure, UndefinedField }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ ObjectMeta, OwnerReference }
import zio.{ IO, ZIO }

/** Common operations for every Kubernetes resource's object
  * @tparam T Kubernetes resource type
  */
trait K8sObject[T] {

  /** Gets the metadata of the object
    */
  def metadata(obj: T): Optional[ObjectMeta]

  /** Maps the metadata of the object, constructing a new object with the modified metadata
    * @param f Function returning the modified metadata
    */
  def mapMetadata(f: ObjectMeta => ObjectMeta)(r: T): T

  /** Gets the name stored in the metadata of the object or fails with [[UndefinedField]] if it is not present.
    */
  def getName(obj: T): IO[K8sFailure, String] =
    ZIO.fromEither(metadata(obj).flatMap(_.name).toRight(UndefinedField("metadata.name")))

  /** Gets the UID stored in the metadata of the object or fails with [[UndefinedField]] if it is not present.
    */
  def getUid(obj: T): IO[K8sFailure, String] =
    ZIO.fromEither(metadata(obj).flatMap(_.uid).toRight(UndefinedField("metadata.uid")))

  /** Gets the metadata of the object or fails with [[UndefinedField]] if it is not present.
    */
  def getMetadata(obj: T): IO[K8sFailure, ObjectMeta] =
    ZIO.fromEither(metadata(obj).toRight(UndefinedField("metadata")))

  /** Gets the generation of the object stored in its metadata or 0 if it is not present
    * (the resource was not uploaded yet)
    */
  def generation(obj: T): Long = metadata(obj).flatMap(_.generation).getOrElse(0L)

  /** Attach another Kubernetes resource as the owner of the given one
    * @param obj Object to attach the owner to
    * @param ownerName Owner's name
    * @param ownerUid Owner's UID
    * @param ownerType Owner's resource type
    * @return The modified object with the attached owner
    */
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

  /** Try to [[attachOwner]] another Kubernetes resource as the owner of the given one,
    * can fail with [[UndefinedField]] if some of the metadata fields are not present.
    */
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

  /** Check if a resource is owned by an other one
    * @param obj Owned resource object to check
    * @param owner Owner
    * @tparam OwnerT Owner resource type
    * @return True if owner owns obj
    */
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

  /** Extension methods for resource types implementing the [[K8sObject]] type class
    */
  implicit class Ops[T](protected val obj: T)(implicit protected val impl: K8sObject[T])
      extends K8sObjectOps[T]
}
