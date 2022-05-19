package com.coralogix.zio.k8s.client.model

import zio.test.Assertion.equalTo
import zio.test.{ ZIOSpecDefault, _ }

object FieldSelectorSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] =
    suite("FieldSelector")(
      test("equals")(
        assert((field("metadata.name") === "value1").asQuery)(equalTo("metadata.name==value1"))
      ),
      test("not equals")(
        assert((field("metadata.name") !== "value1").asQuery)(equalTo("metadata.name!=value1"))
      ),
      test("and") {
        val sel: FieldSelector =
          (field("metadata.name") === "value1") &&
            (field("some.other.field") !== "value2")

        assert(sel.asQuery)(equalTo("metadata.name==value1,some.other.field!=value2"))
      }
    )
}
