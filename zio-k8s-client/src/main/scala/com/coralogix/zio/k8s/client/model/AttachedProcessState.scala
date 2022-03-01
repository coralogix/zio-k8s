package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.K8sFailure
import zio.{Chunk, Promise, Queue}
import zio.stream.UStream

case class AttachedProcessState(
  stdin: Option[Queue[Chunk[Byte]]],
  stdout: Option[UStream[Byte]],
  stderr: Option[UStream[Byte]],
  status: Promise[K8sFailure, Option[Chunk[Byte]]]
)
