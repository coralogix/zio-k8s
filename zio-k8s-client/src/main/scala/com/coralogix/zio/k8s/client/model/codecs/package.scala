package com.coralogix.zio.k8s.client.model

import io.circe.{ Decoder, Encoder }
import zio.Chunk

import java.util.Base64
import scala.util.Try

package object codecs {

  implicit val chunkByteDecoder: Decoder[Chunk[Byte]] =
    Decoder.decodeString.emapTry(str => Try(Base64.getDecoder.decode(str)).map(Chunk.fromArray))

  implicit val chunkByteEncoder: Encoder[Chunk[Byte]] =
    Encoder.encodeString.contramap(bytes => Base64.getEncoder.encodeToString(bytes.toArray))

}
