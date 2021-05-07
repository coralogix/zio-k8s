package com.coralogix.zio.k8s.client.test

import com.coralogix.zio.k8s.client.model.K8sObject._
import com.coralogix.zio.k8s.client.model.{
  Added,
  Deleted,
  FieldSelector,
  K8sNamespace,
  K8sObject,
  K8sResourceType,
  LabelSelector,
  ListResourceVersion,
  Modified,
  Optional,
  PropagationPolicy,
  TypedWatchEvent
}
import com.coralogix.zio.k8s.client.{
  DecodedFailure,
  K8sFailure,
  K8sRequestInfo,
  NotFound,
  Resource,
  ResourceDeleteAll
}
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, Status }
import sttp.model.StatusCode
import zio.duration.Duration
import zio.stm.{ TMap, TQueue, ZSTM }
import zio.stream._
import zio.{ IO, ZIO }

/** Implementation of [[Resource]] and [[ResourceDeleteAll]] to be used from unit tests
  * @param store Object store
  * @param events Watch event queue
  * @tparam T Resource type
  */
final class TestResourceClient[T: K8sObject] private (
  store: TMap[String, T],
  events: TQueue[TypedWatchEvent[T]]
) extends Resource[T] with ResourceDeleteAll[T] {

  override def getAll(
    namespace: Option[K8sNamespace],
    chunkSize: Int,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None,
    resourceVersion: ListResourceVersion = ListResourceVersion.MostRecent
  ): Stream[K8sFailure, T] = {
    // TODO: support fieldSelector, labelSelector and resourceVersion

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
    resourceVersion: Option[String],
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): Stream[K8sFailure, TypedWatchEvent[T]] =
    // TODO: support fieldSelector, labelSelector
    ZStream.fromTQueue(events)

  override def get(name: String, namespace: Option[K8sNamespace]): IO[K8sFailure, T] = {
    val prefix = keyPrefix(namespace)
    store.get(prefix + name).commit.flatMap {
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(NotFound)
    }
  }

  private def increaseResourceVersion(resource: T): T = {
    val resourceVersion = resource.metadata.flatMap(_.resourceVersion)
    val newResourceVersion =
      resourceVersion match {
        case Optional.Present(value) =>
          (value.toInt + 1).toString
        case Optional.Absent         => "0"
      }
    resource.mapMetadata(_.copy(resourceVersion = newResourceVersion))
  }

  override def create(
    newResource: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] = {
    val prefix = keyPrefix(namespace)
    if (!dryRun) {
      val finalResource = increaseResourceVersion(newResource)
      for {
        name <- finalResource.getName
        stm   = for {
                  _ <- store.contains(prefix + name).flatMap {
                         case true  =>
                           ZSTM.fail(
                             DecodedFailure(
                               K8sRequestInfo(K8sResourceType("test", "group", "version"), "create"),
                               Status(),
                               StatusCode.Conflict
                             )
                           )
                         case false => ZSTM.unit
                       }
                  _ <- store.put(prefix + name, finalResource)
                  _ <- events.offer(Added(finalResource))
                } yield ()
        _    <- stm.commit
      } yield finalResource
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
      val finalResource = increaseResourceVersion(updatedResource)
      val stm = for {
        _ <- store.get(prefix + name).flatMap {
               case Some(existing)
                   if existing.metadata.flatMap(_.resourceVersion) != updatedResource.metadata
                     .flatMap(_.resourceVersion) =>
                 ZSTM.fail(
                   DecodedFailure(
                     K8sRequestInfo(K8sResourceType("test", "group", "version"), "replace"),
                     Status(),
                     StatusCode.Conflict
                   )
                 )
               case _ => ZSTM.unit
             }
        _ <- store.put(prefix + name, finalResource)
        _ <- events.offer(Modified(finalResource))
      } yield finalResource
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

  override def deleteAll(
    deleteOptions: DeleteOptions,
    namespace: Option[K8sNamespace],
    dryRun: Boolean,
    gracePeriod: Option[Duration],
    propagationPolicy: Option[PropagationPolicy],
    fieldSelector: Option[FieldSelector],
    labelSelector: Option[LabelSelector]
  ): IO[K8sFailure, Status] = {
    // TODO: support fieldSelector, labelSelector
    val prefix = keyPrefix(namespace)
    if (!dryRun) {
      val stm = for {
        keys        <- store.keys
        filteredKeys = keys.filter(_.startsWith(prefix))
        _           <- ZSTM.foreach_(filteredKeys) { key =>
                         for {
                           item <- store.get(key)
                           _    <- ZSTM.foreach_(item) { item =>
                                     for {
                                       _ <- store.delete(key)
                                       _ <- events.offer(Deleted(item))
                                     } yield ()
                                   }
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

  /** Creates a test implementation of [[Resource]] and [[ResourceDeleteAll]] to be used from unit tests
    * @tparam T Resource type
    * @return Test client
    */
  def make[T: K8sObject]: ZIO[Any, Nothing, TestResourceClient[T]] =
    for {
      store  <- TMap.empty[String, T].commit
      events <- TQueue.unbounded[TypedWatchEvent[T]].commit
    } yield new TestResourceClient[T](store, events)
}
