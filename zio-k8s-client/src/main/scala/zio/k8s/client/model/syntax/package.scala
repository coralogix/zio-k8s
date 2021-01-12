package zio.k8s.client.model

import zio.k8s.model.pkg.apis.meta.v1.ObjectMeta

package object syntax {
  implicit class ObjectOps[T <: Object](value: T) {
    def mapMetadata(
      f: ObjectMeta => ObjectMeta
    )(implicit transformations: ObjectTransformations[T]): T =
      transformations.mapMetadata(f)(value)
  }
}
