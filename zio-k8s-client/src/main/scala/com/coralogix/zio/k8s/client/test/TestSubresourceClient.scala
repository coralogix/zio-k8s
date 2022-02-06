package com.coralogix.zio.k8s.client.test

import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.{ K8sFailure, NotFound, Subresource }
import zio.stm.TMap
import zio.stream.{ ZPipeline, ZStream }
import zio.{ IO, ZIO }

/** Test implementation of [[Subresource]] to be used from unit tests
  * @param store
  *   Subresource data store
  * @tparam T
  *   Subresource type
  */
final class TestSubresourceClient[T] private (store: TMap[String, T]) extends Subresource[T] {
  override def get(
    name: String,
    namespace: Option[K8sNamespace],
    customParameters: Map[String, String] = Map.empty
  ): IO[K8sFailure, T] = {
    val prefix = keyPrefix(namespace)
    store.get(prefix + name).commit.flatMap {
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(NotFound)
    }
  }

  override def streamingGet(
    name: String,
    namespace: Option[K8sNamespace],
    pipeline: ZPipeline[Any, K8sFailure, Byte, T],
    customParameters: Map[String, String]
  ): ZStream[Any, K8sFailure, T] =
    ZStream.fromZIO(get(name, namespace, customParameters))

  override def replace(
    name: String,
    updatedValue: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] = {
    val prefix = keyPrefix(namespace)
    if (!dryRun) {
      store.put(prefix + name, updatedValue).as(updatedValue).commit
    } else {
      ZIO.succeed(updatedValue)
    }
  }

  override def create(
    name: String,
    value: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T] = {
    val prefix = keyPrefix(namespace)
    if (!dryRun) {
      store.put(prefix + name, value).as(value).commit
    } else {
      ZIO.succeed(value)
    }
  }

  private def keyPrefix(namespace: Option[K8sNamespace]): String =
    namespace.map(_.value).getOrElse("") + ":"
}

object TestSubresourceClient {

  /** Creates a test implementation of [[Subresource]] to be used in unit tests
    * @tparam T
    *   Subresource type
    * @return
    *   Test client
    */
  def make[T]: ZIO[Any, Nothing, TestSubresourceClient[T]] =
    for {
      store <- TMap.empty[String, T].commit
    } yield new TestSubresourceClient[T](store)
}
