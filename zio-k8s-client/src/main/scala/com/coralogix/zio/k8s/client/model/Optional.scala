package com.coralogix.zio.k8s.client.model

import io.circe.{ Decoder, Encoder }

import scala.language.implicitConversions

sealed trait Optional[+A] { self =>
  def toOption: Option[A] = self match {
    case Optional.Present(get) => Some(get)
    case Optional.Absent       => None
  }

  def getOrElse[A0 >: A](default: => A0): A0 =
    self match {
      case Optional.Present(get) => get
      case Optional.Absent       => default
    }

  def map[B](f: A => B): Optional[B] =
    self match {
      case Optional.Present(get) => Optional.Present(f(get))
      case Optional.Absent       => Optional.Absent
    }

  def flatMap[B](f: A => Optional[B]): Optional[B] =
    self match {
      case Optional.Present(get) => f(get)
      case Optional.Absent       => Optional.Absent
    }

  def toRight[L](left: L): Either[L, A] =
    self match {
      case Optional.Present(get) => Right(get)
      case Optional.Absent       => Left(left)
    }
}
object Optional {
  final case class Present[+A](get: A) extends Optional[A]
  case object Absent extends Optional[Nothing]

  implicit def AllValuesAreNullable[A](value: A): Optional[A] = Present(value)
  implicit def OptionIsNullable[A](value: Option[A]): Optional[A] =
    value match {
      case Some(value) => Present(value)
      case None        => Absent
    }

  implicit def encoder[A: Encoder]: Encoder[Optional[A]] =
    Encoder.encodeOption[A].contramap(_.toOption)
  implicit def decoder[A: Decoder]: Decoder[Optional[A]] =
    Decoder.decodeOption[A].map(OptionIsNullable)
}
