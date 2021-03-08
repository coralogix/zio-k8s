package com.coralogix.zio.k8s.client.model

import zio.Chunk

/** Composable label selector
  *
  * Use the label constructor [[LabelSelector.Syntax.label]] imported through the
  * [[com.coralogix.zio.k8s.client.model]] to define labels, and the operators
  * defined on them to construct label selectors from them.
  */
sealed trait LabelSelector { self =>

  /** Serializes the field selector into a query parameter
    */
  def asQuery: String

  /** Use this AND another label selector together
    */
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

      /** Label must be equal to the given value
        */
      def ===(value: String): LabelEquals

      /** Label must be equal to one of the given values
        */
      def in(values: String*): LabelIn

      /** Label must not be equal to any of the given values
        */
      def notIn(values: String*): LabelNotIn
    }

    /** Defines a label
      */
    def label(label: String): Label = new Label {
      override def ===(value: String): LabelEquals = LabelEquals(label, value)
      override def in(values: String*): LabelIn = LabelIn(label, values.toSet)
      override def notIn(values: String*): LabelNotIn = LabelNotIn(label, values.toSet)
    }
  }
}
