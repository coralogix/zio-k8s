package com.coralogix.zio.k8s.codegen.internal

import scala.meta.*
import _root_.io.github.vigoo.metagen.core.ScalaType

case class SubresourceId(
  name: String,
  model: ScalaType,
  actionVerbs: Set[String],
  customParameters: Map[String, Type]
) {
  def toMethodParameters: List[Term.Param] =
    customParameters.toList
      .map {
        case (paramName, paramType @ t"Option[$_]") =>
          param"${Term.Name(paramName)}: $paramType = None"
        case (paramName, paramType)                 =>
          param"${Term.Name(paramName)}: $paramType"
      }

  def toParameterAccess: List[Term] =
    customParameters.keys.toList.map(Term.Name(_))

  def toMapFromParameters: Term =
    if (customParameters.isEmpty) {
      q"Map.empty"
    } else {
      val pairs = customParameters.toList
        .map {
          case (paramName, t"Option[$inner]") =>
            q"${Term.Name(paramName)}.map(value => ${Lit.String(paramName)} -> ${valueToString(inner, "value")})"
          case (paramName, paramType)         =>
            q"Some(${Lit.String(paramName)} -> ${valueToString(paramType, paramName)})"
        }

      q"List(..$pairs).flatten.toMap"
    }

  def hasStreamingGet: Boolean = model == ScalaType.string

  def streamingGetTransducer: Term =
    if (model == ScalaType.string) {
      q"zio.stream.ZPipeline.fromChannel(zio.stream.ZPipeline.utf8Decode.channel.orDie) >>> zio.stream.ZPipeline.splitLines" // TODO: map to CodingFailure, needs metadata access
    } else { q"???" }

  private def valueToString(typ: Type, name: String): Term = {
    val nameTerm = Term.Name(name)
    typ match {
      case t"Boolean" => q"""if ($nameTerm) "true" else "false""""
      case t"String"  => nameTerm
      case _          => q"$nameTerm.toString"
    }
  }
}
