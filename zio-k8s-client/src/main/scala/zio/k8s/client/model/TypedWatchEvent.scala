package zio.k8s.client.model

import zio.k8s.model.pkg.apis.meta.v1.WatchEvent
import io.circe.{ Decoder, Json }
import zio.IO
import zio.k8s.client.{ DeserializationFailure, InvalidEvent, K8sFailure }

sealed trait TypedWatchEvent[+T] {
  val resourceVersion: Option[String]
  val namespace: Option[K8sNamespace]
}

case object Reseted extends TypedWatchEvent[Nothing] {
  override val resourceVersion: Option[String] = None
  override val namespace: Option[K8sNamespace] = None
}

final case class Added[T <: Object](item: T) extends TypedWatchEvent[T] {
  override val resourceVersion: Option[String] = item.metadata.flatMap(_.resourceVersion)
  override val namespace: Option[K8sNamespace] =
    item.metadata.flatMap(_.namespace).map(K8sNamespace.apply)
}

final case class Modified[T <: Object](item: T) extends TypedWatchEvent[T] {
  override val resourceVersion: Option[String] = item.metadata.flatMap(_.resourceVersion)
  override val namespace: Option[K8sNamespace] =
    item.metadata.flatMap(_.namespace).map(K8sNamespace.apply)
}

final case class Deleted[T <: Object](item: T) extends TypedWatchEvent[T] {
  override val resourceVersion: Option[String] = item.metadata.flatMap(_.resourceVersion)
  override val namespace: Option[K8sNamespace] =
    item.metadata.flatMap(_.namespace).map(K8sNamespace.apply)
}

object TypedWatchEvent {
  private def parseOrFail[T: Decoder](json: Json): IO[K8sFailure, T] =
    IO.fromEither(implicitly[Decoder[T]].decodeAccumulating(json.hcursor).toEither)
      .mapError(DeserializationFailure.apply)

  def from[T <: Object: Decoder](
    event: WatchEvent
  ): IO[K8sFailure, TypedWatchEvent[T]] =
    event.`type` match {
      case "ADDED" =>
        parseOrFail[T](event.`object`.value).map(Added.apply[T])
      case "MODIFIED" =>
        parseOrFail[T](event.`object`.value).map(Modified.apply[T])
      case "DELETED" =>
        parseOrFail[T](event.`object`.value).map(Deleted.apply[T])
      case _ =>
        IO.fail(InvalidEvent(event.`type`))
    }
}
