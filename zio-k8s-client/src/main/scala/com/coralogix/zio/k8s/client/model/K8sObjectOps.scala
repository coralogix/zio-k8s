package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import zio.IO

trait K8sObjectOps[T] {
  protected val obj: T
  protected val impl: K8sObject[T]

  def metadata: Optional[ObjectMeta] =
    impl.metadata(obj)

  def getMetadata: IO[K8sFailure, ObjectMeta] =
    impl.getMetadata(obj)

  def getName: IO[K8sFailure, String] =
    impl.getName(obj)

  def getUid: IO[K8sFailure, String] =
    impl.getUid(obj)

  def generation: Long =
    impl.generation(obj)

  def mapMetadata(f: ObjectMeta => ObjectMeta): T =
    impl.mapMetadata(f)(obj)

  def attachOwner(
    ownerName: String,
    ownerUid: String,
    ownerType: K8sResourceType
  ): T = impl.attachOwner(obj)(ownerName, ownerUid, ownerType)

  def tryAttachOwner[OwnerT: K8sObject: ResourceMetadata](owner: OwnerT): IO[K8sFailure, T] =
    impl.tryAttachOwner(obj)(owner)

  def isOwnedBy[OwnerT: K8sObject: ResourceMetadata](owner: OwnerT): Boolean =
    impl.isOwnedBy(obj)(owner)
}
