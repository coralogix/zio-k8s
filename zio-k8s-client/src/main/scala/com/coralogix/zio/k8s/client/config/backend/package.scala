package com.coralogix.zio.k8s.client.config

import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import zio.Task

package object backend {
  // trait serving as alias helping with Scala 3 compilation issues
  trait SttpStreamsAndWebSockets extends SttpBackend[Task, ZioStreams with WebSockets]
}
