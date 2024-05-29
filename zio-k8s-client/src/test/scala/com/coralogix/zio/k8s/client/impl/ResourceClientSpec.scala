package com.coralogix.zio.k8s.client.impl

import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.client.{ErrorEvent, Gone, K8sFailure}
import com.coralogix.zio.k8s.model.core.v1.Node
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ObjectMeta, Status, WatchEvent}
import com.coralogix.zio.k8s.model.pkg.runtime.RawExtension
import io.circe.syntax._
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.HttpError
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.client3.testing.RecordingSttpBackend
import sttp.model.Uri.QuerySegment.KeyValue
import sttp.model._
import zio.Task
import zio.stream.ZStream
import zio.test.{Spec, _}

object ResourceClientSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] =
    suite("ResourceClient")(
      parseError,
      watchResources,
    )

  private def nodeToRawExtension(node: Node): RawExtension = RawExtension(node.asJson)
  private val ResourceType = K8sResourceType("test", "group", "version")

  /**
   * Simulate k8s sending an ADDED event followed by an ERROR. Tests that:
   *
   * 1. The watch ends. (If it didn't, the test would never finish.)
   * 2. The ERROR event is parsed into the right class.
   */
  val parseError: Spec[TestEnvironment, Any] = test("Ensure errors are parsed correctly") {
    val addedEvt = WatchEvent(nodeToRawExtension(Node(Some(ObjectMeta(name = "node1", resourceVersion = "1")))), "ADDED")

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
        |}""".stripMargin.replace("\n", "")

    val resp = addedEvt.asJson.noSpaces + "\n" + errorStr
    val testingBackend = new RecordingSttpBackend[Task, ZioStreams with WebSockets](
      HttpClientZioBackend.stub
        .whenAnyRequest
        .thenRespond(Right(ZStream.fromIterable(resp.getBytes)))
    )

    val cluster = K8sCluster(Uri("http://foo/bar"), None)
    val rc = new ResourceClient[Node, Status](ResourceType, cluster, testingBackend)

    for {
      eventsAndFailures <- rc.watch(None, None, sendInitialEvents=true).either.runCollect
    } yield {
      val interactions = testingBackend.allInteractions
      val failures: List[K8sFailure] = eventsAndFailures.toList.collect{ case Left(x) => x }
      assertTrue (
        interactions.size == 1,
        eventsAndFailures.length == 2,
        failures.length == 1,
        failures.head == ErrorEvent(status, message, reason, code)
      )
    }
  }

  /**
   * Simulates the k8s server responding three times, with:
   *
   * 1. Some events but no bookmark.
   * 2. Some events and a bookmark.
   * 3. Some events and then an error.
   *
   * We test that:
   *
   * 1. The watch is continued when there is no error (but the stream is closed).
   * 2. sendInitialEvents=true the first and second times, but not the third (because we got a bookmark).
   * 3. resourceVersion is empty the first time, but increments on subsequent calls based on events / bookmarks.
   *
   * Note that this is not how a real k8s server would behave. In particular, it's not honoring the resourceVersions
   * we request. It's important that the client behave properly anyway, so we don't artificially add that constraint.
   */
  val watchResources: Spec[TestEnvironment, Any] = test("Ensure that sendInitialEvents and resourceVersion are set correctly") {
    val addedEvt = WatchEvent(nodeToRawExtension(Node(Some(ObjectMeta(name = "node1", resourceVersion = "1")))), "ADDED")
    val modifiedEvt = WatchEvent(nodeToRawExtension(Node(Some(ObjectMeta(name = "node1", resourceVersion = "2")))), "MODIFIED")
    val bookmarkEvt = WatchEvent(nodeToRawExtension(Node(Some(ObjectMeta(name = "node2", resourceVersion = "3")))), "BOOKMARK")

    val resp1 = Seq(addedEvt, modifiedEvt)
      .map(_.asJson.noSpaces)
      .mkString("\n")

    val resp2 = Seq(addedEvt, modifiedEvt, bookmarkEvt)
      .map(_.asJson.noSpaces)
      .mkString("\n")

    val testingBackend = new RecordingSttpBackend[Task, ZioStreams with WebSockets](
      HttpClientZioBackend.stub
        .whenAnyRequest
        .thenRespondCyclic(
          Right(ZStream.fromIterable(resp1.getBytes)),
          Right(ZStream.fromIterable(resp2.getBytes)),
          Left(HttpError("Gone for some reason", StatusCode.Gone))
        )
    )

    val cluster = K8sCluster(Uri("http://foo/bar"), None)
    val rc = new ResourceClient[Node, Status](ResourceType, cluster, testingBackend)

    for {
      eventsAndFailures <- rc.watch(None, None, sendInitialEvents=true).either.runCollect
    } yield {
      val interactions = testingBackend.allInteractions
      val events: List[TypedWatchEvent[Node]] = eventsAndFailures.toList.collect{ case Right(x) => x }
      val failures: List[K8sFailure] = eventsAndFailures.toList.collect{ case Left(x) => x }
      val sendInitialEvents = interactions.map(
        _._1.uri
          .querySegments
          .exists {
            case KeyValue(k, v, _, _) if k == "sendInitialEvents" && v == "true" => true
            case _ => false
          }
      )
      val resourceVersions = interactions.map(
        _._1.uri
          .querySegments
          .collect {
            case KeyValue(k, v, _, _) if k == "resourceVersion" => Integer.parseInt(v)
          }
      )
      assertTrue (
        // The HTTP server was called 3 times.
        interactions.size == 3,
        // Added, Modified, Added, Modified, Gone.
        eventsAndFailures.length == 5,
        events.length == 4,
        events.map(_.resourceVersion.get).map(Integer.parseInt) == List(1, 2, 1, 2),
        failures.length == 1,
        failures.head == Gone,
        // sendInitialEvents should only be disabled after the first bookmark (after the second call).
        sendInitialEvents == List(true, true, false),
        // On the first call, unset. Then we get as far as rv 2, and finally the bookmark with 3.
        resourceVersions == List(List.empty, List(2), List(3))
      )
    }
  }
}
