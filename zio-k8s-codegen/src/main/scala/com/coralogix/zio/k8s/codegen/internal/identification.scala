package com.coralogix.zio.k8s.codegen.internal

import io.swagger.v3.oas.models.{ Operation, PathItem }
import io.swagger.v3.oas.models.media.{ ArraySchema, Schema }
import io.swagger.v3.oas.models.parameters.Parameter

import java.util
import scala.collection.JavaConverters._
import scala.util.Try

sealed trait Identified {
  def flatRefs: Set[String]
  def deepRefs(
    definitions: Map[String, IdentifiedSchema],
    alreadyProcessed: Set[String]
  ): Set[String] =
    flatRefs.foldLeft(Set.empty[String]) { case (result, ref) =>
      val name = ref.drop("#/components/schemas/".length)
      if (!alreadyProcessed.contains(name))
        definitions.get(name) match {
          case Some(idef) =>
            (result + name) union idef.deepRefs(definitions, result + name)
          case None       =>
            println(s"!!! Cannot find reference $ref")
            result
        }
      else
        result
    }
}

sealed trait IdentifiedSchema extends Identified {
  val name: String
  val schema: Schema[_]

  def flatRefs: Set[String] =
    IdentifiedSchema.flatRefsOf(schema)
}

object IdentifiedSchema {
  def flatRefsOf(schema: Schema[_]): Set[String] = {
    val self = Option(schema.get$ref()).toSet
    val params = Option(schema.getProperties)
      .map(_.asScala.values.toList)
      .getOrElse(List.empty)
      .flatMap { param =>
        Option(param.get$ref()) match {
          case Some(ref) => Option(ref)
          case None      =>
            param.getType match {
              case "array" =>
                val arraySchema = param.asInstanceOf[ArraySchema]
                Option(arraySchema.getItems).flatMap(items => Option(items.get$ref()))
              case _       =>
                None
            }
        }
      }
      .toSet

    self union params
  }

  def identifyDefinition(name: String, schema: Schema[_]): Set[IdentifiedSchema] = {
    def identifyOne(schema: Schema[_], desc: Map[String, AnyRef]): IdentifiedSchema =
      (for {
        group   <- desc.get("group").map(_.asInstanceOf[String])
        kind    <- desc.get("kind").map(_.asInstanceOf[String])
        version <- desc.get("version").map(_.asInstanceOf[String])
      } yield IdentifiedDefinition(name, group, kind, version, schema))
        .getOrElse(Regular(name, schema))

    (for {
      extensions <- Option(schema.getExtensions)
      descs      <- extensions.asScala.get("x-kubernetes-group-version-kind")
      descsArray <- Try(descs.asInstanceOf[util.ArrayList[_]]).toOption
      result      = descsArray.asScala.foldLeft(Set.empty[IdentifiedSchema]) { case (result, desc) =>
                      result + identifyOne(
                        schema,
                        desc.asInstanceOf[util.LinkedHashMap[String, Object]].asScala.toMap
                      )
                    }
    } yield result).getOrElse(Set(Regular(name, schema)))
  }
}

case class Regular(name: String, schema: Schema[_]) extends IdentifiedSchema
case class IdentifiedDefinition(
  name: String,
  group: String,
  kind: String,
  version: String,
  schema: Schema[_]
) extends IdentifiedSchema {
  def apiVersion: String =
    if (group.nonEmpty)
      s"$group/$version"
    else
      version
}

sealed trait IdentifiedPath extends Identified {
  val op: Operation

  def flatRefs: Set[String] = {
    val paramRefs = Option(op.getParameters)
      .map(_.asScala.toList)
      .getOrElse(List.empty)
      .flatMap(param =>
        Option(param.getSchema).map(IdentifiedSchema.flatRefsOf).getOrElse(Set.empty)
      )
      .toSet

    val bodyRef =
      for {
        requestBody  <- Option(op.getRequestBody)
        content      <- Option(requestBody.getContent)
        firstContent <- content.asScala.values.headOption
        schema       <- Option(firstContent.getSchema)
        ref          <- Option(schema.get$ref())
      } yield ref

    paramRefs union bodyRef.toSet
  }
}

case class RegularAction(name: String, op: Operation, parameters: List[Parameter])
    extends IdentifiedPath
case class IdentifiedAction(
  name: String,
  group: String,
  kind: String,
  version: String,
  action: String,
  method: PathItem.HttpMethod,
  op: Operation,
  outerParameters: List[Parameter]
) extends IdentifiedPath

object IdentifiedPath {

  def identifyPath(path: String, item: PathItem): Set[IdentifiedPath] = {
    def identifyOne(
      item: PathItem,
      params: List[Parameter],
      method: PathItem.HttpMethod,
      op: Operation
    ): IdentifiedPath =
      (for {
        extensions <- Option(op.getExtensions)
        descs      <- extensions.asScala.get("x-kubernetes-group-version-kind")
        descsMap    = descs.asInstanceOf[util.LinkedHashMap[String, Object]].asScala
        group      <- descsMap.get("group").map(_.asInstanceOf[String])
        kind       <- descsMap.get("kind").map(_.asInstanceOf[String])
        version    <- descsMap.get("version").map(_.asInstanceOf[String])
        action     <- extensions.asScala.get("x-kubernetes-action").map(_.asInstanceOf[String])
      } yield IdentifiedAction(path, group, kind, version, action, method, op, params)).getOrElse(
        RegularAction(path, op, params)
      )

    val params = Option(item.getParameters).map(_.asScala.toList).getOrElse(List.empty)
    val ops = item.readOperationsMap.asScala

    ops.map { case (method, op) => identifyOne(item, params, method, op) }.toSet
  }
}
