package com.coralogix.zio.k8s.client.test

import com.coralogix.zio.k8s.client.model.{
  FieldSelector,
  LabelSelector,
  ListResourceVersion,
  Modified
}
import com.coralogix.zio.k8s.model.core.v1.Node
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, ObjectMeta, Status }
import zio.Chunk
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestEnvironment

object TestResourceClientSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Any] = suite("TestResourceClient spec")(
    suite("filter")(
      suite("labelSelector")(
        test("equals") {
          val label1 = "label1"
          val value1 = "value1"
          val label2 = "label2"
          val value2 = "value2"

          val node = Node(
            metadata = ObjectMeta(labels = Map(label1 -> value1, label2 -> value2))
          )

          val labelSelector1 = LabelSelector.LabelEquals(label1, value1)
          val labelSelector2 = LabelSelector.LabelEquals(label2, value1)

          assertTrue(TestResourceClient.filterByLabelSelector(Some(labelSelector1))(node)) &&
          assertTrue(!TestResourceClient.filterByLabelSelector(Some(labelSelector2))(node)) &&
          assertTrue(TestResourceClient.filterByFieldSelector(None)(node))
        },
        test("in") {
          val label1 = "label1"
          val value1 = "value1"
          val label2 = "label2"
          val value2 = "value2"

          val node = Node(
            metadata = ObjectMeta(labels = Map(label1 -> value1, label2 -> value2))
          )

          val labelSelector1 = LabelSelector.LabelIn(label1, Set(value1, value2))
          val labelSelector2 = LabelSelector.LabelIn(label2, Set(value1))

          assertTrue(TestResourceClient.filterByLabelSelector(Some(labelSelector1))(node)) &&
          assertTrue(!TestResourceClient.filterByLabelSelector(Some(labelSelector2))(node))
        },
        test("notIn") {
          val label1 = "label1"
          val value1 = "value1"
          val label2 = "label2"
          val value2 = "value2"

          val node = Node(
            metadata = ObjectMeta(labels = Map(label1 -> value1, label2 -> value2))
          )

          val labelSelector1 = LabelSelector.LabelNotIn(label1, Set(value1))
          val labelSelector2 = LabelSelector.LabelNotIn(label2, Set(value1))

          assertTrue(!TestResourceClient.filterByLabelSelector(Some(labelSelector1))(node)) &&
          assertTrue(TestResourceClient.filterByLabelSelector(Some(labelSelector2))(node))
        },
        test("and") {
          val label1 = "label1"
          val value1 = "value1"
          val label2 = "label2"
          val value2 = "value2"

          val node = Node(
            metadata = ObjectMeta(labels = Map(label1 -> value1, label2 -> value2))
          )

          val labelSelector11 = LabelSelector.LabelEquals(label1, value1)
          val labelSelector12 = LabelSelector.LabelNotIn(label2, Set(value1))
          val labelSelector1 = LabelSelector.And(Chunk(labelSelector11, labelSelector12))

          val labelSelector21 = LabelSelector.LabelEquals(label1, value1)
          val labelSelector22 = LabelSelector.LabelIn(label2, Set(value1))
          val labelSelector2 = LabelSelector.And(Chunk(labelSelector21, labelSelector22))

          assertTrue(TestResourceClient.filterByLabelSelector(Some(labelSelector1))(node)) &&
          assertTrue(!TestResourceClient.filterByLabelSelector(Some(labelSelector2))(node))
        }
      ),
      suite("fieldSelector")(
        test("equals") {
          val field1 = Chunk("metadata", "name")
          val value1 = "value1"
          val field2 = Chunk("metadata", "clusterName")
          val value2 = "value2"

          val node = Node(
            metadata = ObjectMeta(name = value1, clusterName = value2)
          )

          val fieldSelector1 = FieldSelector.FieldEquals(field1, value1)
          val fieldSelector2 = FieldSelector.FieldEquals(field2, value1)

          assertTrue(TestResourceClient.filterByFieldSelector(Some(fieldSelector1))(node)) &&
          assertTrue(!TestResourceClient.filterByFieldSelector(Some(fieldSelector2))(node)) &&
          assertTrue(TestResourceClient.filterByFieldSelector(None)(node))
        },
        test("notEquals") {
          val field1 = Chunk("metadata", "name")
          val value1 = "value1"
          val field2 = Chunk("metadata", "clusterName")
          val value2 = "value2"

          val node = Node(
            metadata = ObjectMeta(name = value1, clusterName = value2)
          )

          val fieldSelector1 = FieldSelector.FieldNotEquals(field1, value1)
          val fieldSelector2 = FieldSelector.FieldNotEquals(field2, value1)

          assertTrue(!TestResourceClient.filterByFieldSelector(Some(fieldSelector1))(node)) &&
          assertTrue(TestResourceClient.filterByFieldSelector(Some(fieldSelector2))(node))
        },
        test("and") {
          val field1 = Chunk("metadata", "name")
          val value1 = "value1"
          val field2 = Chunk("metadata", "clusterName")
          val value2 = "value2"

          val node = Node(
            metadata = ObjectMeta(name = value1, clusterName = value2)
          )

          val fieldSelector11 = FieldSelector.FieldEquals(field1, value1)
          val fieldSelector12 = FieldSelector.FieldNotEquals(field2, value1)
          val fieldSelector1 = FieldSelector.And(Chunk(fieldSelector11, fieldSelector12))

          val fieldSelector21 = FieldSelector.FieldEquals(field1, value1)
          val fieldSelector22 = FieldSelector.FieldNotEquals(field2, value2)
          val fieldSelector2 = FieldSelector.And(Chunk(fieldSelector21, fieldSelector22))

          assertTrue(TestResourceClient.filterByFieldSelector(Some(fieldSelector1))(node)) &&
          assertTrue(!TestResourceClient.filterByFieldSelector(Some(fieldSelector2))(node))
        }
      ),
      suite("resourceVersion")(
        testM("Exact") {
          val name = "name"
          val v1 = "v1"
          val v2 = "v2"
          val generation1 = 1L
          val generation2 = 2L
          val node1 = Node(
            metadata = ObjectMeta(name = name, generation = generation1, resourceVersion = v1)
          )
          val node2 = Node(
            metadata = ObjectMeta(name = name, generation = generation2, resourceVersion = v2)
          )

          assertM(
            TestResourceClient
              .filterByResourceVersion(ZStream(Chunk(node1, node2)))(ListResourceVersion.Exact(v1))
              .runCollect
          )(equalTo(Chunk(node1)))

        },
        testM("NotOlderThan") {
          val name = "name"
          val v1 = "v1"
          val v2 = "v2"
          val v3 = "v3"
          val generation1 = 1L
          val generation2 = 2L
          val generation3 = 3L

          val node1 = Node(
            metadata = ObjectMeta(name = name, generation = generation1, resourceVersion = v1)
          )
          val node2 = Node(
            metadata = ObjectMeta(name = name, generation = generation2, resourceVersion = v2)
          )
          val node3 = Node(
            metadata = ObjectMeta(name = name, generation = generation3, resourceVersion = v3)
          )

          assertM(
            TestResourceClient
              .filterByResourceVersion(ZStream(Chunk(node1, node2, node3)))(
                ListResourceVersion.NotOlderThan(v2)
              )
              .runCollect
          )(equalTo(Chunk(node3)))
        },
        testM("MostRecent") {
          val name = "name"
          val v1 = "v1"
          val v2 = "v2"
          val generation1 = 1L
          val generation2 = 2L
          val node1 = Node(
            metadata = ObjectMeta(name = name, generation = generation1, resourceVersion = v1)
          )
          val node2 = Node(
            metadata = ObjectMeta(name = name, generation = generation2, resourceVersion = v2)
          )

          assertM(
            TestResourceClient
              .filterByResourceVersion(ZStream(Chunk(node1, node2)))(ListResourceVersion.MostRecent)
              .runCollect
          )(equalTo(Chunk(node2)))
        },
        testM("Any") {
          val name = "name"
          val v1 = "v1"
          val v2 = "v2"
          val generation1 = 1L
          val generation2 = 2L
          val node1 = Node(
            metadata = ObjectMeta(name = name, generation = generation1, resourceVersion = v1)
          )
          val node2 = Node(
            metadata = ObjectMeta(name = name, generation = generation2, resourceVersion = v2)
          )

          assertM(
            TestResourceClient
              .filterByResourceVersion(ZStream(Chunk(node1, node2)))(ListResourceVersion.Any)
              .runCollect
          )(equalTo(Chunk(node2)))
        }
      ),
      testM("getAll") {
        val label = "label1"
        val value = "value1"
        val value2 = "value2"
        val name = "name"
        val name2 = "name2"
        val version = "0"

        val node = Node(
          metadata = ObjectMeta(labels = Map(label -> value), name = name)
        )
        val node2 = Node(
          metadata = ObjectMeta(labels = Map(label -> value), name = name2)
        )
        val node3 = Node(
          metadata = ObjectMeta(labels = Map(label -> value2), name = name)
        )

        val labelSelector = LabelSelector.LabelEquals(label, value)
        val fieldSelector = FieldSelector.FieldEquals(Chunk("metadata", "name"), name)
        val resourceVersion = ListResourceVersion.Exact(version)

        for {
          client  <-
            TestResourceClient.make[Node, Status](() => Status())
          created <- client.create(node, None)
          _       <- client.create(node2, None)
          // bump version
          updated <- client.replace(name, created, None)
          // bum version and change label
          _       <- client.replace(
                       name,
                       node3.mapMetadata(
                         _.copy(resourceVersion = updated.metadata.flatMap(_.resourceVersion))
                       ),
                       None
                     )
          nodes   <- client
                       .getAll(
                         None,
                         10,
                         fieldSelector = Some(fieldSelector),
                         labelSelector = Some(labelSelector),
                         resourceVersion = resourceVersion
                       )
                       .runCollect
        } yield assertTrue(nodes == Chunk(created))
      },
      testM("watch") {
        val label = "label1"
        val value = "value1"
        val value2 = "value2"
        val name = "name"
        val name2 = "name2"
        val version = "0"

        val node = Node(
          metadata = ObjectMeta(labels = Map(label -> value), name = name)
        )
        val node2 = Node(
          metadata = ObjectMeta(labels = Map(label -> value), name = name2)
        )
        val node3 = Node(
          metadata = ObjectMeta(labels = Map(label -> value2), name = name)
        )

        val labelSelector = LabelSelector.LabelEquals(label, value)
        val fieldSelector = FieldSelector.FieldEquals(Chunk("metadata", "name"), name)

        for {
          client  <-
            TestResourceClient.make[Node, Status](() => Status())
          created <- client.create(node, None)
          _       <- client.create(node2, None)
          // bump version and change label
          updated <- client.replace(
                       name,
                       node3.mapMetadata(
                         _.copy(resourceVersion = created.metadata.flatMap(_.resourceVersion))
                       ),
                       None
                     )
          // bump version and revert label
          latest  <- client.replace(
                       name,
                       updated.mapMetadata(_.copy(labels = Map(label -> value))),
                       None
                     )
          // filter out only latest modification
          nodes   <- client
                       .watch(
                         None,
                         fieldSelector = Some(fieldSelector),
                         labelSelector = Some(labelSelector),
                         resourceVersion = latest.metadata.flatMap(_.resourceVersion).toOption
                       )
                       .take(1)
                       .runCollect
        } yield assertTrue(nodes == Chunk(Modified(latest)))
      },
      testM("deleteAll") {
        val label = "label1"
        val value = "value1"
        val value2 = "value2"
        val name = "name"
        val name2 = "name2"

        val node = Node(
          metadata = ObjectMeta(labels = Map(label -> value), name = name)
        )
        val node2 = Node(
          metadata = ObjectMeta(labels = Map(label -> value), name = name2)
        )
        val node3 = Node(
          metadata = ObjectMeta(labels = Map(label -> value2), name = name)
        )

        val labelSelector = LabelSelector.LabelEquals(label, value)
        val fieldSelector = FieldSelector.FieldEquals(Chunk("metadata", "name"), name)

        for {
          client    <-
            TestResourceClient.make[Node, Status](() => Status())
          created   <- client.create(node, None)
          created2  <- client.create(node2, None)
          modified2 <- client.replace(name2, created2, None)
          // bump version and change label
          updated   <- client.replace(
                         name,
                         node3.mapMetadata(
                           _.copy(resourceVersion = created.metadata.flatMap(_.resourceVersion))
                         ),
                         None
                       )
          // bump version and revert label
          latest    <- client.replace(
                         name,
                         updated.mapMetadata(_.copy(labels = Map(label -> value))),
                         None
                       )
          // filter out only latest modification
          _         <- client
                         .deleteAll(
                           DeleteOptions(),
                           None,
                           fieldSelector = Some(fieldSelector),
                           labelSelector = Some(labelSelector)
                         )
          nodes     <- client.getAll(None).runCollect
        } yield assertTrue(nodes == Chunk(modified2))
      }
    ),
    testM("get") {
      val name = "name"
      val node = Node(
        metadata = ObjectMeta(name = name)
      )

      for {
        client   <-
          TestResourceClient.make[Node, Status](() => Status())
        created  <- client.create(node, None)
        modified <- client.replace(name, created, None)
        latest   <- client.get(name, None)
      } yield assertTrue(modified == latest)
    },
    testM("delete") {
      import com.coralogix.zio.k8s.client.K8sFailure.syntax._
      val name = "name"
      val node = Node(
        metadata = ObjectMeta(name = name)
      )

      for {
        client  <-
          TestResourceClient.make[Node, Status](() => Status())
        created <- client.create(node, None)
        _       <- client.replace(name, created, None)
        _       <- client.delete(name, DeleteOptions(), None)
        latest  <- client.get(name, None).ifFound
      } yield assertTrue(latest.isEmpty)
    }
  )
}
