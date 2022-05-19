package com.coralogix.zio.k8s.quicklens

import com.softwaremill.quicklens._
import zio.prelude.data.Optional
import zio.test.Assertion._
import zio.test.{ ZIOSpecDefault, _ }

object QuicklensOptionalSpec extends ZIOSpecDefault {

  case class X(inner: Optional[Y])
  case class Y(leaf: Optional[Int])

  override def spec: Spec[TestEnvironment, Any] =
    suite("Quicklens support")(
      test("works on optionals") {
        val a = X(Y(None))
        val f = modify(_: X)(_.inner.each.leaf).setTo(5)
        val g = modify(_: X)(_.inner.each.leaf.each)(_ + 1)
        val b = g(f(a))

        assert(b)(equalTo(X(Y(6))))
      }
    )
}
