package com.coralogix.zio.k8s.client.model.primitives

import io.circe._

trait ValueTypeClasses[Outer, Inner] {
  def encoder: Encoder[Outer]
  def decoder: Decoder[Outer]
}

object ValueTypeClasses {
  def apply[Outer, Inner: Encoder: Decoder](
    pack: => Inner => Outer,
    unpack: => Outer => Inner
  ): ValueTypeClasses[Outer, Inner] =
    new ValueTypeClasses[Outer, Inner] {
      implicit final val encoder: Encoder[Outer] = implicitly[Encoder[Inner]].contramap(unpack)
      implicit final val decoder: Decoder[Outer] = implicitly[Decoder[Inner]].map(pack)
    }
}

trait ValueTypeImplicits[Outer, Inner] {
  val typeClasses: ValueTypeClasses[Outer, Inner]

  implicit val encoder: Encoder[Outer] = typeClasses.encoder
  implicit val decoder: Decoder[Outer] = typeClasses.decoder
}

final case class FieldName(name: String) extends AnyVal
object FieldName extends ValueTypeImplicits[FieldName, String] {
  lazy val typeClasses = ValueTypeClasses(FieldName.apply, _.name)
}

final case class MethodName(name: String) extends AnyVal
object MethodName extends ValueTypeImplicits[MethodName, String] {
  lazy val typeClasses = ValueTypeClasses(MethodName.apply, _.name)
}

final case class ApplicationName(value: String) extends AnyVal
object ApplicationName extends ValueTypeImplicits[ApplicationName, String] {
  lazy val typeClasses = ValueTypeClasses(ApplicationName.apply, _.value)
}

final case class ComputerName(name: String) extends AnyVal
object ComputerName extends ValueTypeImplicits[ComputerName, String] {
  lazy val typeClasses = ValueTypeClasses(ComputerName.apply, _.name)
}

final case class SubsystemName(value: String) extends AnyVal
object SubsystemName extends ValueTypeImplicits[SubsystemName, String] {
  lazy val typeClasses = ValueTypeClasses(SubsystemName.apply, _.value)
}

final case class CategoryName(name: String) extends AnyVal
object CategoryName extends ValueTypeImplicits[CategoryName, String] {
  lazy val typeClasses = ValueTypeClasses(CategoryName.apply, _.name)
}

final case class ThreadId(id: String) extends AnyVal
object ThreadId extends ValueTypeImplicits[ThreadId, String] {
  lazy val typeClasses = ValueTypeClasses(ThreadId.apply, _.id)
}

final case class RuleGroupName(value: String) extends AnyVal
object RuleGroupName extends ValueTypeImplicits[RuleGroupName, String] {
  override lazy val typeClasses = ValueTypeClasses(RuleGroupName.apply, _.value)
}

final case class RuleGroupId(value: String) extends AnyVal
object RuleGroupId extends ValueTypeImplicits[RuleGroupId, String] {
  override lazy val typeClasses = ValueTypeClasses(RuleGroupId.apply, _.value)
}

final case class RuleName(value: String) extends AnyVal
object RuleName extends ValueTypeImplicits[RuleName, String] {
  override lazy val typeClasses = ValueTypeClasses(RuleName.apply, _.value)
}

final case class RuleId(value: String) extends AnyVal
object RuleId extends ValueTypeImplicits[RuleId, String] {
  override lazy val typeClasses = ValueTypeClasses(RuleId.apply, _.value)
}
