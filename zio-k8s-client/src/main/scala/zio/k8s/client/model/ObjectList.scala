package zio.k8s.client.model

import io.circe._
import io.circe.syntax._
import zio.k8s.model.pkg.apis.meta.v1.ListMeta

case class ObjectList[+T](metadata: Option[ListMeta], items: List[T])

object ObjectList {
  implicit def encodeObjectList[T: Encoder]: Encoder[ObjectList[T]] =
    lst =>
      Json.obj(
        "metadata" := lst.metadata,
        "items"    := lst.items
      )

  implicit def decodeObjectList[T: Decoder]: Decoder[ObjectList[T]] =
    (c: HCursor) =>
      for {
        metadata <- c.downField("metadata").as[Option[ListMeta]]
        items    <- c.downField("items").as[List[T]]
      } yield ObjectList(metadata, items)

}
