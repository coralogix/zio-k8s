package com.coralogix.zio.k8s.client.model

import zio.Chunk

sealed trait LabelSelector { self =>
  def asQuery: String

  def &&(other: LabelSelector): LabelSelector.And =
    (self, other) match {
      case (LabelSelector.And(selectors1), LabelSelector.And(selectors2)) =>
        LabelSelector.And(selectors = selectors1 ++ selectors2)
      case (LabelSelector.And(selectors1), _)                             =>
        LabelSelector.And(selectors = selectors1 :+ other)
      case (_, LabelSelector.And(selectors2))                             =>
        LabelSelector.And(selectors = other +: selectors2)
      case (_, _)                                                         =>
        LabelSelector.And(Chunk(self, other))
    }
}

object LabelSelector {
  final case class LabelEquals(label: String, value: String) extends LabelSelector {
    override def asQuery: String = s"$label=$value"
  }
  final case class LabelIn(label: String, values: Set[String]) extends LabelSelector {
    override def asQuery: String = s"$label in (${values.mkString(", ")})"
  }
  final case class LabelNotIn(label: String, values: Set[String]) extends LabelSelector {
    override def asQuery: String = s"$label notin (${values.mkString(", ")})"
  }
  final case class And(selectors: Chunk[LabelSelector]) extends LabelSelector {
    override def asQuery: String = selectors.map(_.asQuery).mkString(",")
  }

  trait Syntax {
    trait Label {
      def ===(value: String): LabelEquals
      def in(values: String*): LabelIn
      def notIn(values: String*): LabelNotIn
    }

    def label(label: String): Label = new Label {
      override def ===(value: String): LabelEquals = LabelEquals(label, value)
      override def in(values: String*): LabelIn = LabelIn(label, values.toSet)
      override def notIn(values: String*): LabelNotIn = LabelNotIn(label, values.toSet)
    }
  }
}
