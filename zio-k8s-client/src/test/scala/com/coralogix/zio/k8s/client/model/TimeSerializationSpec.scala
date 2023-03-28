package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.MicroTime
import io.circe._
import io.circe.syntax._
import zio.Clock
import zio.test.Assertion._
import zio.test.TestAspect.withLiveClock
import zio.test.{ ZIOSpecDefault, _ }

import java.time.OffsetDateTime

object TimeSerializationSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment, Any] =
    suite("MicroTime serialization")(
      test("produce json string in the expected format") {
        val offsetDateTime = OffsetDateTime.parse("2021-05-05T14:36:10.348652378Z")
        val microTime = MicroTime(offsetDateTime)
        val json = microTime.asJson.asString

        assert(json)(isSome(equalTo("2021-05-05T14:36:10.348652Z")))
      },
      test("can print and parse") {
        for {
          now       <- Clock.currentDateTime
          microTime1 = MicroTime(now)
          json       = microTime1.asJson
          microTime2 = json.as[MicroTime]
        } yield assert(microTime2.map(_.value.toEpochSecond))(
          isRight(equalTo(microTime1.value.toEpochSecond))
        )
      } @@ withLiveClock,
      test("Can read time with 6 digit fraction part") {
        val json = Json.fromString("2021-03-06T12:09:50.348652Z")
        assert(json.as[MicroTime])(isRight(anything))
      },
      test("Can read time with 9 digit fraction part") {
        val json = Json.fromString("2021-03-06T12:09:50.348652378Z")
        assert(json.as[MicroTime])(isRight(anything))
      },
      test("Can read time without fraction part") {
        val json = Json.fromString("2021-03-06T12:09:50Z")
        assert(json.as[MicroTime])(isRight(anything))
      }
    )
}
