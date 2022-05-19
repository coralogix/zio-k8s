package com.coralogix.zio.k8s.client.internal

import io.circe.Decoder
import sttp.client3.IsOption
import zio.prelude.data.Optional
import com.coralogix.zio.k8s.client.model._

/** Helper to disambiguate decoder and isOption instances of Option and Optional */
trait IsOptional[T] {
  type Inner
  def decoder: Decoder[T]
  def isOption: IsOption[T]
}

object IsOptional {
  implicit def optional[A: Decoder]: IsOptional[Optional[A]] = new IsOptional[Optional[A]] {
    override type Inner = A

    override def decoder: Decoder[Optional[A]] =
      optionalDecoder[A]

    override def isOption: IsOption[Optional[A]] =
      optionalIsOption[A]
  }

  implicit def notOptional[A: Decoder]: IsOptional[A] = new IsOptional[A] {
    override type Inner = A

    override def decoder: Decoder[A] =
      implicitly

    override def isOption: IsOption[A] =
      implicitly
  }
}
