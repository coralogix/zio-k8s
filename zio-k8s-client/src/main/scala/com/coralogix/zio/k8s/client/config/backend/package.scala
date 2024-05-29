package com.coralogix.zio.k8s.client.config

import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import zio.Task

package object backend {
  // wrapper helping with Scala 3 compilation issues
  case class K8sBackend(value: SttpBackend[Task, ZioStreams with WebSockets]) extends AnyVal
}
