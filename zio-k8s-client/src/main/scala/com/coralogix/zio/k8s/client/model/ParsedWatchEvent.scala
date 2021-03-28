package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.{
  DeserializationFailure,
  InvalidEvent,
  K8sFailure,
  K8sRequestInfo
}
import com.coralogix.zio.k8s.client.model.K8sObject._
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.WatchEvent
import io.circe.{ Decoder, Json }
import zio.IO

/** Watch event with parsed payload
  *
  * This type is used internally by the watch stream. End users should
  * use the [[TypedWatchEvent]] type instead, which does not contain
  * the bookmark event which is transparently handled by the client.
  *
  * @tparam T Payload type
  */
sealed trait ParsedWatchEvent[+T]

/** Parsed typed watch event
  * @param event Payload
  * @tparam T Payload type
  */
final case class ParsedTypedWatchEvent[T](event: TypedWatchEvent[T]) extends ParsedWatchEvent[T]

/** Bookmark event
  * @param resourceVersion Resource version to bookmark
  */
final case class ParsedBookmark(resourceVersion: String) extends ParsedWatchEvent[Nothing]

object ParsedWatchEvent {
  private def parseOrFail[T: Decoder](requestInfo: K8sRequestInfo, json: Json): IO[K8sFailure, T] =
    IO.fromEither(implicitly[Decoder[T]].decodeAccumulating(json.hcursor).toEither)
      .mapError(DeserializationFailure(requestInfo, _))

  /** Converts an unparsed Kubernetes [[com.coralogix.zio.k8s.model.pkg.apis.meta.v1.WatchEvent]] to [[ParsedWatchEvent]]
    * @param event Unparsed event
    * @tparam T Payload type
    * @return Parsed event
    */
  def from[T: K8sObject: Decoder](
    requestInfo: K8sRequestInfo,
    event: WatchEvent
  ): IO[K8sFailure, ParsedWatchEvent[T]] =
    event.`type` match {
      case "ADDED"    =>
        parseOrFail[T](requestInfo, event.`object`.value).map(obj =>
          ParsedTypedWatchEvent(Added(obj))
        )
      case "MODIFIED" =>
        parseOrFail[T](requestInfo, event.`object`.value).map(obj =>
          ParsedTypedWatchEvent(Modified(obj))
        )
      case "DELETED"  =>
        parseOrFail[T](requestInfo, event.`object`.value).map(obj =>
          ParsedTypedWatchEvent(Deleted(obj))
        )
      case "BOOKMARK" =>
        for {
          item            <- parseOrFail[T](requestInfo, event.`object`.value)
          metadata        <- item.getMetadata
          resourceVersion <- metadata.getResourceVersion
        } yield ParsedBookmark(resourceVersion)
      case _          =>
        IO.fail(InvalidEvent(requestInfo, event.`type`))
    }
}
