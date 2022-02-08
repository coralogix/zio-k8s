package com.coralogix.zio.k8s.client.test

import com.coralogix.zio.k8s.client.model.K8sObject._
import com.coralogix.zio.k8s.client.model.LabelSelector.{ And, LabelEquals, LabelIn, LabelNotIn }
import com.coralogix.zio.k8s.client.model.ListResourceVersion.{
  Any,
  Exact,
  MostRecent,
  NotOlderThan
}
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
  Reseted,
  TypedWatchEvent
}
import com.coralogix.zio.k8s.client.{
  DecodedFailure,
  K8sFailure,
  K8sRequestInfo,
  NotFound,
  Resource,
  ResourceDelete,
  ResourceDeleteAll
}
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, ObjectMeta, Status }
import io.circe.{ Json, JsonNumber }
import sttp.model.StatusCode
import zio.duration.Duration
import zio.stm.{ TMap, TQueue, ZSTM }
import zio.stream._
import zio.{ Chunk, IO, ZIO }

import scala.annotation.tailrec

/** Implementation of [[Resource]] and [[ResourceDeleteAll]] to be used from unit tests
  * @param store
  *   Object store
  * @param events
  *   Watch event queue
  * @tparam T
  *   Resource type
  * @tparam DeleteResult
  *   Result of the delete operation
  */
final class TestResourceClient[T: K8sObject, DeleteResult] private (
  store: TMap[String, T],
  events: TQueue[TypedWatchEvent[T]],
  createDeleteResult: () => DeleteResult
) extends Resource[T] with ResourceDelete[T, DeleteResult] with ResourceDeleteAll[T] {

  override def getAll(
    namespace: Option[K8sNamespace],
    chunkSize: Int,
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None,
    resourceVersion: ListResourceVersion = ListResourceVersion.MostRecent
  ): Stream[K8sFailure, T] = {
    val prefix = keyPrefix(namespace)
    filterByResourceVersion(ZStream.unwrap {
      store.toList.commit.map { items =>
        ZStream
          .fromIterable(items)
          .filter { case (key, _) =>
            if (namespace.isDefined) key.startsWith(prefix)
            else true
          }
          .map(_._2)
          .filter(filterByLabelSelector(labelSelector))
          .filter(filterByFieldSelector(fieldSelector))
          .chunkN(chunkSize)
      }
    })(resourceVersion)
  }

  override def watch(
    namespace: Option[K8sNamespace],
    resourceVersion: Option[String],
    fieldSelector: Option[FieldSelector] = None,
    labelSelector: Option[LabelSelector] = None
  ): Stream[K8sFailure, TypedWatchEvent[T]] =
    ZStream.fromTQueue(events).filter {
      case Reseted()      => true
      case Added(item)    =>
        filterByLabelSelector(labelSelector)(item) && filterByFieldSelector(fieldSelector)(item)
      case Modified(item) =>
        filterByLabelSelector(labelSelector)(item) && filterByFieldSelector(fieldSelector)(item)
      case Deleted(item)  =>
        filterByLabelSelector(labelSelector)(item) && filterByFieldSelector(fieldSelector)(item)
    }

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
  ): IO[K8sFailure, DeleteResult] = {
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
      } yield createDeleteResult()
      stm.commit
    } else {
      ZIO.succeed(createDeleteResult())
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
    val prefix = keyPrefix(namespace)
    if (!dryRun) {
      val stm = for {
        keys        <- store.keys
        filteredKeys = keys.filter(_.startsWith(prefix))
        _           <- ZSTM.foreach_(filteredKeys) { key =>
                         for {
                           item <- store.get(key)
                           _    <- ZSTM.foreach_(
                                     item
                                       .filter(filterByLabelSelector(labelSelector))
                                       .filter(filterByFieldSelector(fieldSelector))
                                   ) { item =>
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

  private def selectableByLabel(
    labels: Map[String, String]
  )(labelSelector: LabelSelector): Boolean =
    labelSelector match {
      case LabelEquals(label, value) => labels.get(label).contains(value)
      case LabelIn(label, values)    => labels.get(label).exists(values.contains)
      case LabelNotIn(label, values) => !labels.get(label).exists(values.contains)
      case And(selectors)            => selectors.forall(selectableByLabel(labels))
    }

  @tailrec
  private def getValue(json: Json, fieldPath: List[String]): Json =
    fieldPath match {
      case head :: tail =>
        json.asObject.flatMap(_.apply(head)) match {
          case Some(value) => getValue(value, tail)
          case None        => Json.Null
        }
      case Nil          => json
    }

  private def jsonEqualsTo(json: Json, value: String): Boolean =
    json.asBoolean.exists(_.toString == value) || json.asString.contains(
      value
    ) || json.asNumber == JsonNumber.fromString(value)

  private def selectableByField(json: Json)(fieldSelector: FieldSelector): Boolean =
    fieldSelector match {
      case FieldSelector.FieldEquals(fieldPath, value)    =>
        jsonEqualsTo(getValue(json, fieldPath.toList), value)
      case FieldSelector.FieldNotEquals(fieldPath, value) =>
        !jsonEqualsTo(getValue(json, fieldPath.toList), value)
      case FieldSelector.And(selectors)                   => selectors.forall(selectableByField(json))
    }

  private def filterByFieldSelector(fieldSelector: Option[FieldSelector])(item: T): Boolean =
    if (fieldSelector.isDefined) {
      item.metadata
        .map(ObjectMeta.ObjectMetaEncoder.apply)
        .exists(json => selectableByField(json)(fieldSelector.get))
    } else true

  private def filterByLabelSelector(labelSelector: Option[LabelSelector])(item: T): Boolean =
    if (labelSelector.isDefined)
      item.metadata
        .flatMap(_.labels.map(labels => selectableByLabel(labels)(labelSelector.get)))
        .getOrElse(false)
    else true

  private def filterByResourceVersion(
    stream: Stream[K8sFailure, T]
  )(resourceVersion: ListResourceVersion): ZStream[Any, K8sFailure, T] =
    resourceVersion match {
      case Exact(version)        =>
        stream.filter(_.metadata.flatMap(_.resourceVersion).contains(version))
      case NotOlderThan(version) =>
        ZStream.fromIterableM(
          stream.runCollect.map(
            _.sortBy(_.generation)
              .partition(_.metadata.flatMap(_.resourceVersion).contains(version))
              ._2
          )
        )
      case MostRecent | Any      =>
        ZStream.fromIterableM(
          stream.runCollect.map(
            _.sortBy(_.generation).takeRight(1)
          )
        )
    }
}

object TestResourceClient {

  /** Creates a test implementation of [[Resource]] and [[ResourceDeleteAll]] to be used from unit
    * tests
    * @tparam T
    *   Resource type
    * @tparam DeleteResult
    *   Result type of the delete operation
    * @return
    *   Test client
    */
  def make[T: K8sObject, DeleteResult](
    createDeleteResult: () => DeleteResult
  ): ZIO[Any, Nothing, TestResourceClient[T, DeleteResult]] =
    for {
      store  <- TMap.empty[String, T].commit
      events <- TQueue.unbounded[TypedWatchEvent[T]].commit
    } yield new TestResourceClient[T, DeleteResult](store, events, createDeleteResult)
}
