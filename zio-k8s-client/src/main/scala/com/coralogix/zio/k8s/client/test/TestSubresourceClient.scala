package com.coralogix.zio.k8s.client.test

import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.{ K8sFailure, NotFound, Subresource }
import zio.stm.TMap
import zio.stream.{ ZStream, ZTransducer }
import zio.{ IO, ZIO }

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
    transducer: ZTransducer[Any, K8sFailure, Byte, T],
    customParameters: Map[String, String]
  ): ZStream[Any, K8sFailure, T] =
    ZStream.fromEffect(get(name, namespace, customParameters))

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
  def make[T]: ZIO[Any, Nothing, TestSubresourceClient[T]] =
    for {
      store <- TMap.empty[String, T].commit
    } yield new TestSubresourceClient[T](store)
}
