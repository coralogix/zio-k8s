package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.{readTextFile, writeTextFile}
import com.coralogix.zio.k8s.codegen.internal.Conversions.splitName
import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import zio.ZIO
import zio.blocking.Blocking
import zio.nio.core.file.Path

import java.nio.file.{Paths => JPaths, Path => JPath}

import scala.meta.Tree
import scala.meta.internal.prettyprinters.TreeSyntax

trait Common {
  protected def findStatusEntity(
                                  definitions: Map[String, IdentifiedSchema],
                                  modelName: String
                                ): Option[String] = {
    val modelSchema = definitions(modelName).schema.asInstanceOf[ObjectSchema]
    findStatusEntityOfSchema(modelSchema)
  }

  protected def findStatusEntityOfSchema(modelSchema: ObjectSchema): Option[String] =
    for {
      properties <- Option(modelSchema.getProperties)
      statusPropSchema <- Option(properties.get("status"))
      ref <- Option(statusPropSchema.get$ref())
      (pkg, name) = splitName(ref.drop("#/components/schemas/".length))
    } yield pkg.mkString(".") + "." + name

  def scalaVersion: String

  protected def prettyPrint(tree: Tree): String = {
    val dialect =
      if (scalaVersion.startsWith("3.")) scala.meta.dialects.Scala3
      else if (scalaVersion.startsWith("2.13.")) scala.meta.dialects.Scala213
      else scala.meta.dialects.Scala212
    val prettyprinter = TreeSyntax[Tree](dialect)
    prettyprinter(tree).toString
  }

  protected def format(scalafmt: Scalafmt, path: Path): ZIO[Blocking, Throwable, Path] = {
    if (scalaVersion.startsWith("3."))
      ZIO.succeed(path) // NOTE: no formatting for scala 3 yet
    else
      for {
        code <- readTextFile(path)
        formatted = scalafmt.format(JPaths.get(".scalafmt.conf"), path.toFile.toPath, code)
        _ <- writeTextFile(path, formatted)
      } yield path
  }
}
