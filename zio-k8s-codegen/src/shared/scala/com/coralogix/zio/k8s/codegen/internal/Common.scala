package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.{ readTextFile, writeTextFile }
import com.coralogix.zio.k8s.codegen.internal.Conversions.splitName
import io.github.vigoo.metagen.core._
import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import zio.ZIO
import zio.blocking.Blocking
import zio.nio.file.Path
import zio.prelude._

import java.nio.file.{ Path => JPath, Paths => JPaths }
import scala.meta.{ Pat, Tree }
import scala.meta.internal.prettyprinters.TreeSyntax

trait Common {
  protected def findStatusEntity(
    root: Package,
    definitions: Map[String, IdentifiedSchema],
    modelName: String
  ): Option[ScalaType] = {
    val modelSchema = definitions(modelName).schema.asInstanceOf[ObjectSchema]
    findStatusEntityOfSchema(modelSchema)
  }

  protected def findStatusEntityOfSchema(modelSchema: ObjectSchema): Option[ScalaType] =
    for {
      properties       <- Option(modelSchema.getProperties)
      statusPropSchema <- Option(properties.get("status"))
      ref              <- Option(statusPropSchema.get$ref())
      statusEntity      = splitName(ref.drop("#/components/schemas/".length))
    } yield statusEntity

  def scalaVersion: String

  protected def prettyPrint(tree: Tree): String = {
    val dialect =
      if (scalaVersion.startsWith("3.")) scala.meta.dialects.Scala3
      else if (scalaVersion.startsWith("2.13.")) scala.meta.dialects.Scala213
      else scala.meta.dialects.Scala212
    val prettyprinter = TreeSyntax[Tree](dialect)
    prettyprinter(tree).toString
  }

  protected def format(scalafmt: Scalafmt, path: Path): ZIO[Blocking, Throwable, Path] =
    if (scalaVersion.startsWith("3."))
      ZIO.succeed(path) // NOTE: no formatting for scala 3 yet
    else
      for {
        code     <- readTextFile(path)
        formatted = scalafmt.format(JPaths.get(".scalafmt.conf"), path.toFile.toPath, code)
        _        <- writeTextFile(path, formatted)
      } yield path

  implicit class PackageOps(pkg: Package) {
    def /(subPackages: Vector[String]): Package =
      subPackages.foldLeft(pkg)(_ / _)

    def /(subPackages: Package): Package =
      subPackages.path.foldLeft(pkg)(_ / _)

    def show: String =
      pkg.path.mkString(".")

    def dropPrefix(prefix: Package): Package = {
      val commonPrefixLength =
        prefix.path.zip(pkg.path).prefixLength { case (a, b) => a == b }

      pkg.path.drop(commonPrefixLength) match {
        case Nil             => pkg
        case cons @ ::(_, _) => new Package(NonEmptyList.fromCons(cons))
      }
    }
  }

  implicit class ScalaTypeOps(scalaType: ScalaType) {
    def pat: Pat.Var = Pat.Var(scalaType.termName)

    def renamed(f: String => String): ScalaType =
      ScalaType(scalaType.pkg, f(scalaType.name), scalaType.params: _*)
  }
}
