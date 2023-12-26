package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.writeTextFile
import com.coralogix.zio.k8s.codegen.internal.Conversions.{ splitName, splitNameOld }
import io.github.vigoo.metagen.core.*
import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import zio.ZIO
import zio.ZIO.*
import zio.nio.file.Path
import zio.nio.file.Files

import scala.collection.JavaConverters.*
import scala.meta.Term

trait ZioOpticsGenerator {
  this: Common with ModelGenerator =>

  private val opticsRoot = Package("com", "coralogix", "zio", "k8s", "optics")

  protected def generateAllZioOptics(
    definitions: Set[IdentifiedSchema]
  ): ZIO[Generator, GeneratorFailure[Nothing], Set[Path]] = {
    val filteredDefinitions = definitions.filter(d => !isListModel(d))
    for {
      _     <-
        ZIO.succeed(
          logger.info(s"Generating ZIO Optics for ${filteredDefinitions.size} models...")
        )
      paths <- ZIO.foreach(filteredDefinitions) { d =>
                 val modelEntity = splitName(d.name, modelRootPkg)
                 val opticsEntity = splitName(d.name, opticsRoot)

                 for {
                   _          <-
                     ZIO.succeed(
                       logger.info(
                         s"Generating '${opticsEntity.name}' to ${opticsEntity.pkg.asString}"
                       )
                     )
                   targetPath <-
                     Generator
                       .generateScalaPackage[Any, Nothing](opticsEntity.pkg, opticsEntity.name)(
                         generateZioOptics(
                           modelEntity,
                           opticsEntity,
                           d
                         )
                       )
                 } yield targetPath
               }
    } yield paths
  }

  private def generateZioOptics(
    modelEntity: ScalaType,
    opticsEntity: ScalaType,
    d: IdentifiedSchema
  ): ZIO[CodeFileGenerator, Nothing, Term.Block] = {
    import scala.meta._

    val optics = Option(d.schema.getType) match {
      case Some("object") =>
        val objectSchema = d.schema.asInstanceOf[ObjectSchema]

        val presentHelper = ScalaType(opticsRoot, "present")

        Option(objectSchema.getProperties).map(_.asScala) match {
          case Some(properties) =>
            val requiredProperties = Overrides.requiredFields(d)

            properties
              .filterKeys(filterKeysOf(d))
              .toList
              .flatMap { case (name, propSchema) =>
                val isRequired = requiredProperties.contains(name)
                val prop = toType(name, propSchema)
                val nameP = ScalaType(opticsEntity.pkg / opticsEntity.name, name)
                val nameL = nameP.renamed(_ + "L")
                val nameO = nameP.renamed(_ + "O")

                if (isRequired)
                  List(
                    q"""val ${nameL.pat}: ${Types
                        .zioOpticsLens(modelEntity, prop)
                        .typ} = ${Types.zioOpticsLens_.term}(obj => Right(obj.${nameP.termName}), value => obj => Right(obj.copy(${nameP.termName} = value)))"""
                  )
                else
                  List(
                    q"""val ${nameL.pat}: ${Types
                        .zioOpticsLens(modelEntity, Types.optional(prop))
                        .typ} = ${Types.zioOpticsLens_.term}(obj => Right(obj.${nameP.termName}), value => obj => Right(obj.copy(${nameP.termName} = value)))""",
                    q"""val ${nameO.pat} = ${nameL.term} >>> ${presentHelper.term}[${prop.typ}, ${prop.typ}]"""
                  )
              }
          case _                => List.empty
        }
      case _              => List.empty
    }

    ZIO.succeed {
      Term.Block(List(q"""
          object ${opticsEntity.termName} {
            ..$optics
          }
         """))
    }
  }
}
