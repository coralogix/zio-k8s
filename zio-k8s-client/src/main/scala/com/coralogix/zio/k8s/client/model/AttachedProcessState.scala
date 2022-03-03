package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.K8sFailure
import zio.{ Chunk, Promise, Queue }
import zio.stream.Stream

case class AttachedProcessState(
  stdin: Option[Queue[Chunk[Byte]]],
  stdout: Option[Stream[K8sFailure, Byte]],
  stderr: Option[Stream[K8sFailure, Byte]],
  status: Promise[K8sFailure, Option[Chunk[Byte]]]
)
