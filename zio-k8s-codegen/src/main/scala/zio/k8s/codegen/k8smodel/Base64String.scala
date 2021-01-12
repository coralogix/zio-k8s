package zio.k8s.codegen.k8smodel

import io.circe.{ Decoder, Encoder }

class Base64String(val data: Array[Byte]) extends AnyVal {
  override def toString() = "Base64String(" + data.toString() + ")"
}
object Base64String {
  def apply(bytes: Array[Byte]): Base64String = new Base64String(bytes)
  def unapply(value: Base64String): Option[Array[Byte]] = Some(value.data)

  implicit val guardrailDecodeBase64String: Decoder[Base64String] = Decoder[String]
    .emapTry(v => scala.util.Try(java.util.Base64.getDecoder.decode(v)))
    .map(new Base64String(_))
  implicit val guardrailEncodeBase64String: Encoder[Base64String] =
    Encoder[String].contramap[Base64String](v =>
      new String(java.util.Base64.getEncoder.encode(v.data))
    )
}
