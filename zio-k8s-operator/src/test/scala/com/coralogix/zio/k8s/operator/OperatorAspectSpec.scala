package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.model.core.v1.Pod
import com.coralogix.zio.k8s.operator.Operator._
import com.coralogix.zio.k8s.operator.aspects.logEvents
import zio.ZIO
import zio.Clock
import zio.test.environment.TestEnvironment
import zio.test.{ assertCompletes, ZSpec }
import zio.test.ZIOSpecDefault

object OperatorAspectSpec extends ZIOSpecDefault {
  sealed trait CustomOperatorFailures

  override def spec: ZSpec[TestEnvironment, Any] =
    suite("Operator aspects")(
      test("can @@ the built-in logEvents aspect") {
        val eventProcessor: EventProcessor[Clock, CustomOperatorFailures, Pod] =
          (ctx, event) =>
            event match {
              case Reseted()      =>
                ZIO.unit
              case Added(item)    =>
                ZIO.unit
              case Modified(item) =>
                ZIO.unit
              case Deleted(item)  =>
                ZIO.unit
            }

        val _ = Operator.namespaced(
          eventProcessor @@ logEvents
        )(namespace = None, buffer = 1024)

        assertCompletes
      }
    )
}
