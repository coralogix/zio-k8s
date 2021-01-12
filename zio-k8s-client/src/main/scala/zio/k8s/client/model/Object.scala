package zio.k8s.client.model

import zio.k8s.client.{ K8sFailure, UndefinedField }
import zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import zio.{ IO, ZIO }

/** Object of a Kubernetes resource */
trait Object {
  def metadata: Option[ObjectMeta]

  def getName: IO[K8sFailure, String] =
    ZIO.fromEither(metadata.flatMap(_.name).toRight(UndefinedField("metadata.name")))

  def getUid: IO[K8sFailure, String] =
    ZIO.fromEither(metadata.flatMap(_.uid).toRight(UndefinedField("metadata.uid")))

  def generation: Long = metadata.flatMap(_.generation).getOrElse(0L)
}