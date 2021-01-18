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
