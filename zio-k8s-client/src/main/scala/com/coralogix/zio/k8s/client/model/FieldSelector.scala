package com.coralogix.zio.k8s.client.model

import zio.Chunk

/** A composable field selector
  *
  * Every Kubernetes model's companion object contain a set of [[Field]] definitions. To construct
  * [[FieldSelector]] values for queries, use these [[Field]] values and the operators defined on
  * them.
  *
  * Note that a very small subset of the fields is actually usable as field seletors, but there is
  * no compile-time information about which one of them are. For this reason we provide a [[Field]]
  * for each available field and it is the developer's responsibility to only use supported fields
  * in field selectors, or handle the errors returned by the Kubernetes server.
  */
sealed trait FieldSelector { self =>

  /** Serializes the field selector into a query parameter
    */
  def asQuery: String

  /** Use this AND another field selector together
    */
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

      /** Field must be equal to the given value
        */
      def ===(value: String): FieldEquals

      /** Field must not be equal to the given value
        */
      def !==(value: String): FieldNotEquals
    }

    /** Field constructor, used by the generated companion objects
      */
    def field(path: Chunk[String]): Field = new Field {
      override def ===(value: String): FieldEquals = FieldEquals(path, value)
      override def !==(value: String): FieldNotEquals = FieldNotEquals(path, value)
    }

    /** Field constructor, used by the generated companion objects
      */
    def field(raw: String): Field = field(Chunk.fromArray(raw.split('.')))
  }
}
