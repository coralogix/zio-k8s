package com.coralogix.zio.k8s.client.test

import com.coralogix.zio.k8s.client.model.K8sObject._
import com.coralogix.zio.k8s.client.model.{
  Added,
  Deleted,
  K8sNamespace,
  K8sObject,
  Modified,
  PropagationPolicy,
  TypedWatchEvent
}
import com.coralogix.zio.k8s.client.{ K8sFailure, NotFound, Resource }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, Status }
import zio.duration.Duration
import zio.stm.{ TMap, TQueue, ZSTM }
import zio.stream._
import zio.{ IO, ZIO }

final class TestResourceClient[T: K8sObject] private (
  store: TMap[String, T],
  events: TQueue[TypedWatchEvent[T]]
) extends Resource[T] {

  override def getAll(
    namespace: Option[K8sNamespace],
    chunkSize: Int
  ): Stream[K8sFailure, T] = {
    val prefix = keyPrefix(namespace)
    ZStream.unwrap {
      store.toList.commit.map { items =>
        ZStream
          .fromIterable(items)
          .filter { case (key, _) => if (namespace.isDefined) key.startsWith(prefix) else true }
          .map { case (_, value) => value }
          .chunkN(chunkSize)
      }
    }
  }

  override def watch(
    namespace: Option[K8sNamespace],
    resourceVersion: Option[String]
  ): Stream[K8sFailure, TypedWatchEvent[T]] =
    ZStream.fromTQueue(events)

  override def get(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T] = {
    val prefix = keyPrefix(namespace)
    store.get(prefix + name).commit.flatMap {
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(NotFound)
    }
  }

  override def create(
    newResource: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] = {
    val prefix = keyPrefix(namespace)
    if (!dryRun) {
      for {
        name <- newResource.getName
        stm   = for {
                  _ <- store.put(prefix + name, newResource)
                  _ <- events.offer(Added(newResource))
                } yield ()
        _    <- stm.commit
      } yield newResource
    } else {
      ZIO.succeed(newResource)
    }
  }

  override def replace(
    name: String,
    updatedResource: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] = {
    val prefix = keyPrefix(namespace)
    if (!dryRun) {
      val stm = for {
        _ <- store.put(prefix + name, updatedResource)
        _ <- events.offer(Modified(updatedResource))
      } yield updatedResource
      stm.commit
    } else {
      ZIO.succeed(updatedResource)
    }
  }

  override def delete(
    name: String,
    deleteOptions: DeleteOptions,
    namespace: Option[K8sNamespace],
    dryRun: Boolean,
    gracePeriod: Option[Duration],
    propagationPolicy: Option[PropagationPolicy]
  ): IO[K8sFailure, Status] = {
    val prefix = keyPrefix(namespace)
    if (!dryRun) {
      val stm = for {
        item <- store.get(prefix + name)
        _    <- ZSTM.foreach_(item) { item =>
                  for {
                    _ <- store.delete(prefix + name)
                    _ <- events.offer(Deleted(item))
                  } yield ()
                }
      } yield Status()
      stm.commit
    } else {
      ZIO.succeed(Status())
    }
  }

  private def keyPrefix(namespace: Option[K8sNamespace]): String =
    namespace.map(_.value).getOrElse("") + ":"
}

object TestResourceClient {
  def make[T: K8sObject]: ZIO[Any, Nothing, TestResourceClient[T]] =
    for {
      store  <- TMap.empty[String, T].commit
      events <- TQueue.unbounded[TypedWatchEvent[T]].commit
    } yield new TestResourceClient[T](store, events)
}
