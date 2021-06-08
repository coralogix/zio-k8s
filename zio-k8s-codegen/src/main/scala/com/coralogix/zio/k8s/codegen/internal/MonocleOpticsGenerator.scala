package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.writeTextFile
import com.coralogix.zio.k8s.codegen.internal.Conversions.splitName
import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.ZIO
import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.nio.file.Files

import scala.collection.JavaConverters._

trait MonocleOpticsGenerator {
  this: Common with ModelGenerator =>

  private val monocleRoot = Vector("com", "coralogix", "zio", "k8s", "monocle")

  protected def generateAllOptics(
    scalafmt: Scalafmt,
    targetRoot: Path,
    definitions: Set[IdentifiedSchema]
  ): ZIO[Blocking, Throwable, Set[Path]] = {
    val filteredDefinitions = definitions.filter(d => !isListModel(d))
    for {
      _     <-
        ZIO.effect(
          logger.info(s"Generating Monocle optics for ${filteredDefinitions.size} models...")
        )
      paths <- ZIO.foreach(filteredDefinitions) { d =>
                 val (groupName, entityName) = splitName(d.name)
                 val pkg = (monocleRoot ++ groupName)
                 val modelPkg = (modelRoot ++ groupName)

                 for {
                   _         <- ZIO.effect(logger.info(s"Generating '$entityName' to ${pkg.mkString(".")}"))
                   src        =
                     generateOptics(modelRoot, pkg, modelPkg, entityName, d)
                   targetDir  = pkg.foldLeft(targetRoot)(_ / _)
                   _         <- Files.createDirectories(targetDir)
                   targetPath = targetDir / s"$entityName.scala"
                   _         <- writeTextFile(targetPath, src)
                   _         <- format(scalafmt, targetPath)
                 } yield targetPath
               }
    } yield paths
  }

  private def generateOptics(
    modelRootPackage: Vector[String],
    pkg: Vector[String],
    modelPkg: Vector[String],
    entityName: String,
    d: IdentifiedSchema
  ): String = {
    import scala.meta._
    val modelRootPackageTerm = modelRootPackage.mkString(".").parse[Term].get.asInstanceOf[Term.Ref]
    val modelPackageTerm = modelPkg.mkString(".").parse[Term].get.asInstanceOf[Term.Ref]
    val packageTerm = pkg.mkString(".").parse[Term].get.asInstanceOf[Term.Ref]

    val entityNameT = Type.Name(entityName)
    val entityOpticsN = Term.Name(entityName + "O")

    val optics = Option(d.schema.getType) match {
      case Some("object") =>
        val objectSchema = d.schema.asInstanceOf[ObjectSchema]

        Option(objectSchema.getProperties).map(_.asScala) match {
          case Some(properties) =>
            val requiredProperties = Overrides.requiredFields(d)

            properties
              .filterKeys(filterKeysOf(d))
              .toList
              .flatMap { case (name, propSchema) =>
                val isRequired = requiredProperties.contains(name)
                val propT = toType(name, propSchema)

                val nameN = Term.Name(name)
                val nameLN = Term.Name(name + "L")
                val nameLP = Pat.Var(nameLN)
                val nameON = Term.Name(name + "O")
                val nameOP = Pat.Var(nameON)

                if (isRequired)
                  List(
                    q"""val $nameLP: Lens[$entityNameT, $propT] = GenLens[$entityNameT](_.$nameN)"""
                  )
                else
                  List(
                    q"""val $nameLP: Lens[$entityNameT, Optional[$propT]] = GenLens[$entityNameT](_.$nameN)""",
                    q"""val $nameOP: MonocleOptional[$entityNameT, $propT] = optional($nameLN)"""
                  )
              }
          case _                => List.empty
        }
      case _              => List.empty
    }

    val tree =
      q"""package $packageTerm {

          import io.circe._
          import io.circe.syntax._
          import java.time.OffsetDateTime
          import scala.util.Try
          import zio.{Chunk, IO, ZIO}

          import monocle.{Lens, Traversal}
          import monocle.{Optional => MonocleOptional}
          import monocle.macros.GenLens

          import com.coralogix.zio.k8s.client.model.Optional
          import com.coralogix.zio.k8s.monocle.optional

          import $modelRootPackageTerm._
          import $modelPackageTerm._

          object $entityOpticsN {
            ..${optics}
          }
          }
      """
    prettyPrint(tree)
  }
}
