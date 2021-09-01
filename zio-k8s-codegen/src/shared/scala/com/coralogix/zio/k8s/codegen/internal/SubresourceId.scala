package com.coralogix.zio.k8s.codegen.internal

import scala.meta._

case class SubresourceId(
  name: String,
  modelName: String,
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

  def hasStreamingGet: Boolean = modelName == "String"

  def streamingGetTransducer: Term =
    modelName match {
      case "String" =>
        q"zio.stream.ZTransducer.utf8Decode >>> zio.stream.ZTransducer.splitLines"
      case _        => q"???"
    }

  private def valueToString(typ: Type, name: String): Term = {
    val nameTerm = Term.Name(name)
    typ match {
      case t"Boolean" => q"""if ($nameTerm) "true" else "false""""
      case t"String"  => nameTerm
      case _          => q"$nameTerm.toString"
    }
  }
}
