package com.coralogix.zio.k8s.client.model

import com.coralogix.zio.k8s.client.{ ErrorEvent, K8sRequestInfo }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ ObjectMeta, WatchEvent }
import io.circe.Decoder
import io.circe.parser.decode
import zio.prelude.data.Optional
import zio.test.Assertion.{ equalTo, isLeft }
import zio.test.{ assert, Spec, TestEnvironment, ZIOSpecDefault }

object ParsedWatchEventSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] =
    suite("Parse events")(
      test("Ensure that error events are handled properly") {
        val status = "Failure"
        val message = "too old resource version: 33948840 (34026715)"
        val reason = "Expired"
        val code = 410

        val errorStr = s"""
                          |{
                          |  "type": "ERROR",
                          |  "object": {
                          |    "kind": "Status",
                          |    "apiVersion": "v1",
                          |    "metadata": {},
                          |    "status": "$status",
                          |    "message": "$message",
                          |    "reason": "$reason",
                          |    "code": $code
                          |  }
                          |}""".stripMargin

        val expectedErrEvt = ErrorEvent(status, message, reason, code)

        val event: WatchEvent = decode[WatchEvent](errorStr).fold(
          error => throw new RuntimeException(s"Could not decode watch event: $error"),
          identity
        )

        implicit val WatchEventDecoder: Decoder[None.type] = Decoder.decodeNone
        implicit val k8sObject: K8sObject[None.type] = new K8sObject[None.type] {
          override def metadata(obj: None.type): Optional[ObjectMeta] = ???
          override def mapMetadata(f: ObjectMeta => ObjectMeta)(r: None.type): None.type = None
        }

        val reqInfo: K8sRequestInfo = K8sRequestInfo(K8sResourceType("foo", "bar", "baz"), "get")
        for {
          result <- ParsedWatchEvent.from(reqInfo, event).either
        } yield assert(result)(isLeft(equalTo(expectedErrEvt)))
      }
    )
}
