package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status
import zio.{Chunk, Promise}
import zio.stream.{Sink, UStream}

case class AttachedProcessState(
  stdin: Option[Sink[Nothing, Chunk[Byte], Nothing, Unit]],
  stdout: Option[UStream[Byte]],
  stderr: Option[UStream[Byte]],
  status: Promise[K8sFailure, Option[Status]]
)
