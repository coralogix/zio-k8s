package com.coralogix.zio.k8s.client.config

import java.io.InputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.interfaces.{ ECPublicKey, RSAPublicKey }
import java.security.spec.{ ECPrivateKeySpec, PKCS8EncodedKeySpec, RSAPrivateCrtKeySpec }
import java.security.{ KeyFactory, PrivateKey, PublicKey }
import java.util.{ Arrays, Base64 }
import java.util.regex.Pattern

private[config] trait PrivateKeyDecoder {
  def decode(input: InputStream, publicKey: PublicKey): PrivateKey
}

private[config] object PrivateKeyDecoder extends PrivateKeyDecoder {
  private val PemHeader = Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----")

  override def decode(input: InputStream, publicKey: PublicKey): PrivateKey = {
    val pem = readPem(input)

    pem.label match {
      case "PRIVATE KEY"           => decodePkcs8(pem.bytes, publicKey)
      case "RSA PRIVATE KEY"       => decodeRsaPkcs1(pem.bytes, publicKey)
      case "EC PRIVATE KEY"        => decodeEcSec1(pem.bytes, publicKey)
      case "ENCRYPTED PRIVATE KEY" =>
        fail("Encrypted private keys are not supported")
      case other                   =>
        fail(
          s"Unsupported PEM label '$other'; expected PRIVATE KEY, RSA PRIVATE KEY, or EC PRIVATE KEY"
        )
    }
  }

  private def decodeEcSec1(bytes: Array[Byte], publicKey: PublicKey): PrivateKey =
    publicKey match {
      case ecPublicKey: ECPublicKey =>
        val root = new DerReader(bytes)
        val sequence = root.readSequence("EC private key")
        val version = sequence.readInteger("EC private key version")
        if (version != BigInteger.ONE) {
          fail(s"Unsupported EC private key version $version")
        }

        val scalarBytes = sequence.readOctetString("EC private key scalar")
        if (scalarBytes.isEmpty) {
          fail("EC private key scalar must not be empty")
        }

        // SEC1 permits optional curve parameters and a public key after the private scalar.
        while (sequence.hasRemaining)
          sequence.skipElement(Set(0xa0, 0xa1), "EC private key optional field")
        root.requireEnd("EC private key")

        val scalar = new BigInteger(1, scalarBytes)
        if (scalar.signum() <= 0 || scalar.compareTo(ecPublicKey.getParams.getOrder) >= 0) {
          fail("EC private key scalar is outside the certificate curve's valid range")
        }

        val keySpec = new ECPrivateKeySpec(scalar, ecPublicKey.getParams)
        KeyFactory.getInstance("EC").generatePrivate(keySpec)
      case _                        =>
        fail("An EC PRIVATE KEY requires an EC client certificate")
    }

  private def decodePkcs8(bytes: Array[Byte], publicKey: PublicKey): PrivateKey = {
    // The certificate identifies the algorithm without parsing the PKCS#8 AlgorithmIdentifier.
    val keyFactory = KeyFactory.getInstance(publicKey.getAlgorithm)
    keyFactory.generatePrivate(new PKCS8EncodedKeySpec(bytes))
  }

  private def decodeRsaPkcs1(bytes: Array[Byte], publicKey: PublicKey): PrivateKey =
    publicKey match {
      case rsaPublicKey: RSAPublicKey =>
        val root = new DerReader(bytes)
        val sequence = root.readSequence("RSA private key")
        val version = sequence.readInteger("RSA private key version")
        val modulus = sequence.readPositiveInteger("RSA modulus")
        val publicExponent = sequence.readPositiveInteger("RSA public exponent")
        val keySpec = new RSAPrivateCrtKeySpec(
          modulus,
          publicExponent,
          sequence.readPositiveInteger("RSA private exponent"),
          sequence.readPositiveInteger("RSA prime P"),
          sequence.readPositiveInteger("RSA prime Q"),
          sequence.readPositiveInteger("RSA prime exponent P"),
          sequence.readPositiveInteger("RSA prime exponent Q"),
          sequence.readPositiveInteger("RSA CRT coefficient")
        )

        if (version != BigInteger.ZERO) {
          fail(s"Unsupported RSA private key version $version")
        }
        sequence.requireEnd("RSA private key")
        root.requireEnd("RSA private key")

        if (
          modulus != rsaPublicKey.getModulus ||
          publicExponent != rsaPublicKey.getPublicExponent
        ) {
          fail("RSA private key does not match the client certificate")
        }

        KeyFactory.getInstance("RSA").generatePrivate(keySpec)
      case _                          =>
        fail("An RSA PRIVATE KEY requires an RSA client certificate")
    }

  private def fail(message: String): Nothing =
    throw new IllegalArgumentException(message)

  private def isAsciiWhitespace(character: Char): Boolean =
    character == ' ' || character == '\t' || character == '\r' || character == '\n'

  private def isBase64Character(character: Char): Boolean =
    (character >= 'A' && character <= 'Z') ||
      (character >= 'a' && character <= 'z') ||
      (character >= '0' && character <= '9') ||
      character == '+' || character == '/' || character == '='

  private def readPem(input: InputStream): Pem = {
    val value = new String(input.readAllBytes(), StandardCharsets.US_ASCII)
    val matcher = PemHeader.matcher(value)
    if (!matcher.find()) {
      fail("Private key is not PEM encoded")
    }
    if (!value.substring(0, matcher.start()).forall(isAsciiWhitespace)) {
      fail("Unexpected content before the PEM header")
    }

    val label = matcher.group(1)
    val footer = s"-----END $label-----"
    val footerStart = value.indexOf(footer, matcher.end())
    if (footerStart < 0) {
      fail(s"Missing PEM footer for '$label'")
    }
    if (!value.substring(footerStart + footer.length).forall(isAsciiWhitespace)) {
      fail("Unexpected content after the PEM footer")
    }

    val encoded = value
      .substring(matcher.end(), footerStart)
      .iterator
      .filterNot(isAsciiWhitespace)
      .mkString
    if (encoded.isEmpty || !encoded.forall(isBase64Character)) {
      fail("PEM body is not valid Base64")
    }

    val bytes =
      try Base64.getDecoder.decode(encoded)
      catch {
        case _: IllegalArgumentException => fail("PEM body is not valid Base64")
      }
    Pem(label, bytes)
  }

  private final case class Pem(label: String, bytes: Array[Byte])

  /** Minimal, bounded DER reader for the two traditional private-key formats supported above. */
  private final class DerReader(bytes: Array[Byte]) {
    private var offset = 0

    def hasRemaining: Boolean = offset < bytes.length

    def readInteger(description: String): BigInteger = {
      val encoded = readValue(0x02, description)
      if (encoded.isEmpty) {
        fail(s"$description must not be empty")
      }
      if (
        encoded.length > 1 &&
        ((encoded(0) == 0.toByte && (encoded(1) & 0x80) == 0) ||
          (encoded(0) == 0xff.toByte && (encoded(1) & 0x80) != 0))
      ) {
        fail(s"$description is not minimally encoded")
      }
      new BigInteger(encoded)
    }

    def readOctetString(description: String): Array[Byte] =
      readValue(0x04, description)

    def readPositiveInteger(description: String): BigInteger = {
      val integer = readInteger(description)
      if (integer.signum() <= 0) {
        fail(s"$description must be positive")
      }
      integer
    }

    def readSequence(description: String): DerReader =
      new DerReader(readValue(0x30, description))

    def requireEnd(description: String): Unit =
      if (hasRemaining) {
        fail(s"Unexpected trailing data in $description")
      }

    def skipElement(allowedTags: Set[Int], description: String): Unit = {
      val tag = readUnsignedByte(description)
      if (!allowedTags.contains(tag)) {
        fail(f"Unexpected DER tag 0x$tag%02x in $description")
      }
      skip(readLength(description), description)
    }

    private def readLength(description: String): Int = {
      val first = readUnsignedByte(description)
      if ((first & 0x80) == 0) {
        first
      } else {
        val count = first & 0x7f
        if (count == 0) {
          fail(s"Indefinite DER length is not permitted in $description")
        }
        if (count > 4 || count > bytes.length - offset) {
          fail(s"Invalid DER length in $description")
        }

        var length = 0L
        var index = 0
        while (index < count) {
          val next = readUnsignedByte(description)
          if (index == 0 && next == 0) {
            fail(s"Non-minimal DER length in $description")
          }
          length = (length << 8) | next.toLong
          index += 1
        }
        if (length < 128 || length > Int.MaxValue) {
          fail(s"Invalid DER length in $description")
        }
        length.toInt
      }
    }

    private def readUnsignedByte(description: String): Int = {
      if (!hasRemaining) {
        fail(s"Unexpected end of DER data in $description")
      }
      val value = bytes(offset) & 0xff
      offset += 1
      value
    }

    private def readValue(expectedTag: Int, description: String): Array[Byte] = {
      val tag = readUnsignedByte(description)
      if (tag != expectedTag) {
        fail(f"Unexpected DER tag 0x$tag%02x in $description")
      }
      val length = readLength(description)
      if (length > bytes.length - offset) {
        fail(s"DER length exceeds the available data in $description")
      }
      val value = Arrays.copyOfRange(bytes, offset, offset + length)
      offset += length
      value
    }

    private def skip(length: Int, description: String): Unit = {
      if (length > bytes.length - offset) {
        fail(s"DER length exceeds the available data in $description")
      }
      offset += length
    }
  }
}
