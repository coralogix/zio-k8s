package com.coralogix.zio.k8s.client.model

import zio.test.Assertion.equalTo
import zio.test.{ ZIOSpecDefault, _ }

object LabelSelectorSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] =
    suite("LabelSelector")(
      test("equals")(
        assert((label("lb1") === "value1").asQuery)(equalTo("lb1=value1"))
      ),
      test("in")(
        assert(label("lb1").in("value1", "value2").asQuery)(equalTo("lb1 in (value1, value2)"))
      ),
      test("notIn")(
        assert(label("lb1").notIn("value1", "value2").asQuery)(
          equalTo("lb1 notin (value1, value2)")
        )
      ),
      test("and") {
        val sel: LabelSelector =
          (label("lb1") === "value1") &&
            (label("lb2") === "value2") &&
            label("lb3").notIn("value3")

        assert(sel.asQuery)(equalTo("lb1=value1,lb2=value2,lb3 notin (value3)"))
      }
    )
}
