package com.coralogix.zio.k8s.client.impl

import _root_.io.circe._
import cats.data.NonEmptyList
import com.coralogix.zio.k8s.client.model.{
  AttachedProcessState,
  K8sCluster,
  K8sNamespace,
  K8sResourceType
}
import com.coralogix.zio.k8s.client.{ K8sFailure, K8sRequestInfo, RequestFailure, Subresource }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3._
import sttp.client3.circe._
import sttp.ws.WebSocketFrame
import zio.stream.ZSink.Push
import zio.stream.{ Stream, ZSink, ZStream, ZTransducer }
import zio.{ Chunk, IO, Promise, Queue, Task, UIO, ZIO }

import java.util.Base64
import scala.util.Random

/** Generic implementation for [[Subresource]]
  * @param resourceType
  *   Kubernetes resource metadata
  * @param cluster
  *   Configured Kubernetes cluster
  * @param backend
  *   Configured HTTP client
  * @param subresourceName
  *   Name of the subresource
  * @tparam T
  *   Subresource type
  */
final class SubresourceClient[T: Encoder: Decoder](
  override protected val resourceType: K8sResourceType,
  override protected val cluster: K8sCluster,
  override protected val backend: SttpBackend[Task, ZioStreams with WebSockets],
  subresourceName: String
) extends Subresource[T] with ResourceClientBase {

  def get(
    name: String,
    namespace: Option[K8sNamespace],
    customParameters: Map[String, String] = Map.empty
  ): IO[K8sFailure, T] =
    handleFailures(s"get $subresourceName") {
      k8sRequest
        .get(
          simple(Some(name), Some(subresourceName), namespace)
            .addParams(customParameters)
        )
        .response(asJsonAccumulating[T])
        .send(backend)
    }

  def streamingGet(
    name: String,
    namespace: Option[K8sNamespace],
    transducer: ZTransducer[Any, K8sFailure, Byte, T],
    customParameters: Map[String, String] = Map.empty
  ): ZStream[Any, K8sFailure, T] =
    ZStream.unwrap {
      handleFailures(s"get $subresourceName") {
        k8sRequest
          .get(
            simple(Some(name), Some(subresourceName), namespace)
              .addParams(customParameters)
          )
          .response(
            asEither[ResponseException[
              String,
              NonEmptyList[Error]
            ], ZioStreams.BinaryStream, ZioStreams](
              asStringAlways.mapWithMetadata { case (body, meta) =>
                HttpError(body, meta.code)
                  .asInstanceOf[ResponseException[String, NonEmptyList[Error]]]
              },
              asStreamAlwaysUnsafe(ZioStreams)
            )
          )
          .send(backend)
      }.map { (stream: ZioStreams.BinaryStream) =>
        stream
          .mapError(RequestFailure(K8sRequestInfo(resourceType, s"get $subresourceName"), _))
          .transduce(transducer)
      }
    }

  def replace(
    name: String,
    updatedValue: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] =
    handleFailures(s"replace $subresourceName") {
      k8sRequest
        .put(modifying(name, Some(subresourceName), namespace, dryRun))
        .body(updatedValue)
        .response(asJsonAccumulating[T])
        .send(backend)
    }

  def create(
    name: String,
    value: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] =
    handleFailures(s"create $subresourceName") {
      k8sRequest
        .post(modifying(name, Some(subresourceName), namespace, dryRun))
        .body(value)
        .response(asJsonAccumulating[T])
        .send(backend)
    }

  override def connect(
    name: String,
    namespace: Option[K8sNamespace],
    customParameters: Map[String, String] = Map.empty
  ): IO[K8sFailure, AttachedProcessState] = {

    val queueSize = 1024
    for {
      nonce     <- ZIO
                     .effect {
                       val arr = Array.ofDim[Byte](16)
                       Random.nextBytes(arr)
                       new String(Base64.getEncoder.encode(arr))
                     }
                     .mapError(toK8sError)
      stdin     <- ZIO.effect(customParameters.get("stdin").exists(_.toBoolean)).mapError(toK8sError)
      stdout    <- ZIO.effect(customParameters.get("stdout").exists(_.toBoolean)).mapError(toK8sError)
      stderr    <- ZIO.effect(customParameters.get("stderr").exists(_.toBoolean)).mapError(toK8sError)
      status    <- Promise.make[K8sFailure, Option[Status]]
      in        <- maybeQueue(stdin, queueSize)
      _         <- in.fold(ZIO.unit)(q => status.await.ignore *> q.shutdown).fork
      out       <- maybeQueue(stdout, queueSize)
      err       <- maybeQueue(stderr, queueSize)
      asResponse =
        asWebSocketStream(ZioStreams)(pipe(in, out, err, status)).mapWithMetadata {
          case (body, meta) =>
            body.left.map(error =>
              HttpError(error, meta.code)
                .asInstanceOf[ResponseException[String, NonEmptyList[Error]]]
            )
        }
      _         <-
        handleFailures("connect")(
          k8sRequest
            .get(
              connecting(
                name,
                subresourceName,
                namespace
              ).addParams(customParameters).scheme("wss")
            )
            .header("Sec-WebSocket-Version", "13")
            .header("Sec-WebSocket-Key", nonce)
            .header("Sec-WebSocket-Protocol", "v4.channel.k8s.io")
            .response(asResponse)
            .send(backend)
        ).either.flatMap {
          case Left(failure) =>
            status.fail(failure)
          case Right(_)      =>
            status.succeed(None)
        }.fork
    } yield AttachedProcessState(
      in.map(ZSink.fromQueueWithShutdown),
      out.map(q =>
        ZStream
          .fromQueueWithShutdown(q)
          .interruptWhen(status.await.ignore)
          .flattenChunks
      ),
      err.map(q =>
        ZStream
          .fromQueueWithShutdown(q)
          .interruptWhen(status.await.ignore)
          .flattenChunks
      ),
      status
    )
  }

  private def pipe(
    stdin: Option[Queue[Chunk[Byte]]],
    stdout: Option[Queue[Chunk[Byte]]],
    stderr: Option[Queue[Chunk[Byte]]],
    status: Promise[K8sFailure, Option[Status]]
  )(
    stream: Stream[Throwable, WebSocketFrame.Data[_]]
  ): Stream[Throwable, WebSocketFrame] = {
    sealed trait Message
    object Message {
      final case class Stdout(message: Chunk[Byte]) extends Message
      final case class Stderr(message: Chunk[Byte]) extends Message
      final case class Status(message: Chunk[Byte]) extends Message
      final case class Stdin(message: Chunk[Byte]) extends Message
      final case object Done extends Message
    }

    val inMessageStream = stream.map {
      case WebSocketFrame.Binary(payload, _, _) if (payload.length > 0) =>
        payload(0) match {
          case 1 => Message.Stdout(Chunk.fromArray(payload.drop(1)))
          case 2 => Message.Stderr(Chunk.fromArray(payload.drop(1)))
          case 3 => Message.Status(Chunk.fromArray(payload.drop(1)))
          case _ => Message.Done
        }
      case _                                                            =>
        Message.Done
    }

    val outMessageStream =
      stdin
        .map(q => Stream.fromQueue(q.map(Message.Stdin)))
        .getOrElse(Stream.empty)

    inMessageStream
      .mergeTerminateLeft(outMessageStream)
      .mapM {
        case Message.Stdin(bytes)  =>
          ZIO.some(
            WebSocketFrame
              .Binary((Chunk(0.toByte) ++ bytes).toArray, finalFragment = true, rsv = None)
          )
        case Message.Stdout(bytes) =>
          stdout match {
            case Some(queue) => queue.offer(bytes).as(None)
            case _           => ZIO.none
          }
        case Message.Stderr(bytes) =>
          stderr match {
            case Some(queue) => queue.offer(bytes).as(None)
            case _           => ZIO.none
          }

        case Message.Status(bytes) =>
          for {
            _ <-
              parser
                .decode[Status](new String(bytes.toArray))
                .fold(error => status.fail(toK8sError(error)), value => status.succeed(Some(value)))
          } yield None
        case Message.Done          =>
          for {
            _ <- status.succeed(None)
          } yield None
      }
      .collectSome
  }

  private def maybeQueue(create: Boolean, capacity: Int) =
    if (create) {
      for {
        queue <- Queue.bounded[Chunk[Byte]](capacity)
      } yield Some(queue)
    } else
      ZIO.none

  private def toK8sError(throwable: Throwable) =
    RequestFailure(
      requestInfo = K8sRequestInfo(
        resourceType,
        "connect"
      ),
      reason = throwable
    )

}
