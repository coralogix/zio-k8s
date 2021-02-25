package com.coralogix.zio.k8s.client.model

import zio.Chunk

sealed trait FieldSelector { self =>
  def asQuery: String

  def &&(other: FieldSelector): FieldSelector.And =
    (self, other) match {
      case (FieldSelector.And(selectors1), FieldSelector.And(selectors2)) =>
        FieldSelector.And(selectors = selectors1 ++ selectors2)
      case (FieldSelector.And(selectors1), _)                             =>
        FieldSelector.And(selectors = selectors1 :+ other)
      case (_, FieldSelector.And(selectors2))                             =>
        FieldSelector.And(selectors = other +: selectors2)
      case (_, _)                                                         =>
        FieldSelector.And(Chunk(self, other))
    }
}

object FieldSelector {
  final case class FieldEquals(fieldPath: Chunk[String], value: String) extends FieldSelector {
    override def asQuery: String = s"${fieldPath.mkString(".")}==$value"
  }
  final case class FieldNotEquals(fieldPath: Chunk[String], value: String) extends FieldSelector {
    override def asQuery: String = s"${fieldPath.mkString(".")}!=$value"
  }
  final case class And(selectors: Chunk[FieldSelector]) extends FieldSelector {
    override def asQuery: String = selectors.map(_.asQuery).mkString(",")
  }

  trait Syntax {
    trait Field {
      def ===(value: String): FieldEquals
      def !==(value: String): FieldNotEquals
    }

    def field(path: Chunk[String]): Field = new Field {
      override def ===(value: String): FieldEquals = FieldEquals(path, value)
      override def !==(value: String): FieldNotEquals = FieldNotEquals(path, value)
    }

    def field(raw: String): Field = field(Chunk.fromArray(raw.split('.')))
  }
}
