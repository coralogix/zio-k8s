package zio.k8s.client.internal

import io.circe._

import scala.annotation.tailrec

object CircePrettyFailure {

  private def prettyPrintDecodeFailure(message: String, history: List[CursorOp]): String = {
    val customMessage =
      if (message == "Attempt to decode value on failed cursor") None else Some(message)
    def describeTail: String =
      describePosition(collapseArraySteps(history.tail.reverse), 0).mkString("/")
    def describeFull: String =
      describePosition(collapseArraySteps(history.reverse), 0).mkString("/")
    def prettyFailure(message: => String): String =
      customMessage match {
        case Some(msg) => s"$msg in $describeFull"
        case None      => message
      }
    history.head match {
      case CursorOp.DownField(name) if predefinedDecoderFailureNames.contains(message) =>
        s"$describeFull is not a $message"
      case CursorOp.DownField(name) =>
        prettyFailure(s"Could not find field: '$name' in $describeTail")
      case CursorOp.DownN(n) =>
        prettyFailure(s"Could not find the $n${postfix(n)} element in $describeTail")
      case _ =>
        s"$message: $describeFull"
    }
  }

  private def collapseArraySteps(ops: List[CursorOp]): List[CursorOp] = {
    def go(ops: List[CursorOp], index: Option[Int]): List[CursorOp] =
      index match {
        case Some(currentIndex) =>
          ops match {
            case CursorOp.MoveLeft :: remaining =>
              go(remaining, Some(currentIndex - 1))
            case CursorOp.MoveRight :: remaining =>
              go(remaining, Some(currentIndex + 1))
            case head :: remaining =>
              CursorOp.DownN(currentIndex) :: head :: go(remaining, None)
            case Nil =>
              Nil
          }
        case None =>
          ops match {
            case CursorOp.DownArray :: remaining =>
              go(remaining, Some(0))
            case CursorOp.DownN(n) :: remaining =>
              go(remaining, Some(n))
            case head :: remaining =>
              head :: go(remaining, None)
            case Nil =>
              Nil
          }
      }

    go(ops, None)
  }

  private def describePosition(ops: List[CursorOp], currentIndex: Int): List[String] =
    ops match {
      case Nil => List("root")
      case CursorOp.DownField(name) :: remaining =>
        s"$name" :: describePosition(remaining, currentIndex)
      case CursorOp.DownN(n) :: remaining =>
        s"[$n]" :: describePosition(remaining, currentIndex = n)
      case CursorOp.DownArray :: remaining =>
        s"[0]" :: describePosition(remaining, currentIndex = 0)
      case CursorOp.MoveLeft :: remaining =>
        s"<${currentIndex - 1}]" :: describePosition(remaining, currentIndex - 1)
      case CursorOp.MoveRight :: remaining =>
        s"[${currentIndex + 1}>" :: describePosition(remaining, currentIndex + 1)
      case _ => List(ops.toString()) // TODO: implement for more
    }
  private def postfix(n: Int): String =
    n match {
      case 1 => "st"
      case 2 => "nd"
      case 3 => "rd"
      case _ => "th"
    }
  private val predefinedDecoderFailureNames: Set[String] =
    Set(
      "String",
      "Int",
      "Long",
      "Short",
      "Byte",
      "BigInt",
      "BigDecimal",
      "Boolean",
      "Char",
      "java.lang.Integer",
      "java.lang.Long",
      "java.lang.Short",
      "java.lang.Byte",
      "java.lang.Boolean",
      "java.lang.Character"
    )

  def prettyPrint(error: Error): String =
    error match {
      case DecodingFailure(message, history) => prettyPrintDecodeFailure(message, history)
      case _                                 => error.toString
    }
}
