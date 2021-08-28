package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import zio.IO

/** Extension methods for Kubernetes resources
  *
  * This is a common implementation for all the implicit classes providing extension methods for the
  * generated Kuberentes model types. The extension methods are just delegating the calls to the
  * resource's [[K8sObject]] implementation.
  *
  * @tparam T
  *   Resource type to be extended
  */
trait K8sObjectOps[T] {
  protected val obj: T
  protected val impl: K8sObject[T]

  /** Gets the metadata of the object
    */
  def metadata: Optional[ObjectMeta] =
    impl.metadata(obj)

  /** Gets the metadata of the object and fail with [[com.coralogix.zio.k8s.client.UndefinedField]]
    * if it is not present
    */
  def getMetadata: IO[K8sFailure, ObjectMeta] =
    impl.getMetadata(obj)

  /** Gets the name of the object and fail with [[com.coralogix.zio.k8s.client.UndefinedField]] if
    * it is not present
    */
  def getName: IO[K8sFailure, String] =
    impl.getName(obj)

  /** Gets the UID of the object and fail with [[com.coralogix.zio.k8s.client.UndefinedField]] if it
    * is not present
    */
  def getUid: IO[K8sFailure, String] =
    impl.getUid(obj)

  /** Gets the geneation of the object or 0 if it is new
    */
  def generation: Long =
    impl.generation(obj)

  /** Creates a new object with its metadata modified by the given function f
    * @param f
    *   Function to modify the object's metadata with
    * @return
    *   Object with modified metadata
    */
  def mapMetadata(f: ObjectMeta => ObjectMeta): T =
    impl.mapMetadata(f)(obj)

  /** Attach another resource as the owner of this one
    * @param ownerName
    *   Owner's name
    * @param ownerUid
    *   Owner's UID
    * @param ownerType
    *   Owner's resource type metadata
    * @return
    *   Object with the attached owner described in its metadata
    */
  def attachOwner(
    ownerName: String,
    ownerUid: String,
    ownerType: K8sResourceType
  ): T = impl.attachOwner(obj)(ownerName, ownerUid, ownerType)

  /** Tries to attach another resource as the owner of this one. Can fail with
    * [[com.coralogix.zio.k8s.client.UndefinedField]] if the owner does not have all the required
    * metadata.
    *
    * @param owner
    *   Owner resource
    * @tparam OwnerT
    *   Type of the owner
    * @return
    *   Object with the attached owner described in its metadata
    */
  def tryAttachOwner[OwnerT: K8sObject: ResourceMetadata](owner: OwnerT): IO[K8sFailure, T] =
    impl.tryAttachOwner(obj)(owner)

  /** Verifies ownership between the resources
    * @param owner
    *   Possible owner of this resource
    * @tparam OwnerT
    *   Type of the owner resource
    * @return
    *   True if owner owns this resource
    */
  def isOwnedBy[OwnerT: K8sObject: ResourceMetadata](owner: OwnerT): Boolean =
    impl.isOwnedBy(obj)(owner)
}
