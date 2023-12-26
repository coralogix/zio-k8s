package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.writeTextFile
import com.coralogix.zio.k8s.codegen.internal.Conversions.splitName
import io.github.vigoo.metagen.core.*
import io.swagger.v3.oas.models.media.ObjectSchema
import zio.ZIO
import zio.nio.file.Path
import zio.nio.file.Files

import scala.collection.JavaConverters.*
import scala.meta.Term

trait MonocleOpticsGenerator {
  this: Common & ModelGenerator =>

  private val monocleRoot = Package("com", "coralogix", "zio", "k8s", "monocle")

  protected def generateAllMonocleOptics(
    definitions: Set[IdentifiedSchema]
  ): ZIO[Generator, GeneratorFailure[Nothing], Set[Path]] = {
    val filteredDefinitions = definitions.filter(d => !isListModel(d))
    for {
      _     <-
        ZIO.succeed(
          logger.info(s"Generating Monocle optics for ${filteredDefinitions.size} models...")
        )
      paths <- ZIO.foreach(filteredDefinitions) { d =>
                 val model = splitName(d.name)
                 val monocle = splitName(model.name, monocleRoot)

                 for {
                   _          <-
                     ZIO.succeed(
                       logger.info(s"Generating '${model.name}' to ${monocle.pkg.asString}")
                     )
                   targetPath <- Generator.generateScalaPackage[Any, Nothing](monocle.pkg, model.name)(
                                   generateMonocleOptics(monocle.pkg, model, d)
                                 )
                 } yield targetPath
               }
    } yield paths
  }

  private def generateMonocleOptics(
    pkg: Package,
    model: ScalaType,
    d: IdentifiedSchema
  ): ZIO[CodeFileGenerator, Nothing, Term.Block] = {
    import scala.meta._

    val opticsModel = ScalaType(pkg, model.name + "O")
    val optionalHelper = ScalaType(pkg, "optional")

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
                val prop = toType(name, propSchema)

                val nameN = Term.Name(name)
                val nameLN = Term.Name(name + "L")
                val nameLP = Pat.Var(nameLN)
                val nameON = Term.Name(name + "O")
                val nameOP = Pat.Var(nameON)

                if (isRequired)
                  List(
                    q"""val $nameLP: ${Types.monocleLens(model, prop).typ} = ${Types.monocleGenLens.term}[${model.typ}](_.$nameN)"""
                  )
                else
                  List(
                    q"""val $nameLP: ${Types.monocleLens(model, Types.optional(prop)).typ} = ${Types.monocleGenLens.term}[${model.typ}](_.$nameN)""",
                    q"""val $nameOP: ${Types.monocleOptional(model, prop).typ} = ${optionalHelper.term}($nameLN)"""
                  )
              }
          case _                => List.empty
        }
      case _              => List.empty
    }

    ZIO.succeed {
      Term.Block(List(q"""object ${opticsModel.termName} {
            ..$optics
          }"""))
    }
  }
}
