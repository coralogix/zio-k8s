package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.writeTextFile
import com.coralogix.zio.k8s.codegen.internal.Conversions.{ modelRoot, splitName }
import io.github.vigoo.metagen.core._
import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import zio.{ Has, ZIO }
import zio.blocking.Blocking
import zio.nio.file.Path
import zio.nio.file.Files

import scala.collection.JavaConverters._
import scala.meta.Term

trait ZioOpticsGenerator {
  this: Common with ModelGenerator =>

  private val opticsRoot = Package("com", "coralogix", "zio", "k8s", "optics")

  protected def generateAllZioOptics(
    definitions: Set[IdentifiedSchema]
  ): ZIO[Has[Generator] with Blocking, GeneratorFailure[Nothing], Set[Path]] = {
    val filteredDefinitions = definitions.filter(d => !isListModel(d))
    for {
      _     <-
        ZIO.effectTotal(
          logger.info(s"Generating ZIO Optics for ${filteredDefinitions.size} models...")
        )
      paths <- ZIO.foreach(filteredDefinitions) { d =>
                 val modelEntity = splitName(d.name, modelRoot)
                 val opticsEntity = splitName(d.name, opticsRoot)

                 for {
                   _          <-
                     ZIO.effectTotal(
                       logger.info(s"Generating '${opticsEntity.name}' to ${opticsEntity.pkg.show}")
                     )
                   targetPath <-
                     Generator.generateScalaPackage[Any, Nothing](
                       opticsEntity.pkg,
                       opticsEntity.name
                     ) {
                       generateZioOptics(modelEntity, opticsEntity, d)
                     }
                 } yield targetPath
               }
    } yield paths
  }

  private def generateZioOptics(
    modelEntity: ScalaType,
    opticsEntity: ScalaType,
    d: IdentifiedSchema
  ): ZIO[Has[CodeFileGenerator], Nothing, Term.Block] = {
    import scala.meta._

    val optics = Option(d.schema.getType) match {
      case Some("object") =>
        val objectSchema = d.schema.asInstanceOf[ObjectSchema]

        val opticsEntityO = opticsEntity.renamed(_ + "O")

        Option(objectSchema.getProperties).map(_.asScala) match {
          case Some(properties) =>
            val requiredProperties = Overrides.requiredFields(d)

            properties
              .filterKeys(filterKeysOf(d))
              .toList
              .flatMap { case (name, propSchema) =>
                val isRequired = requiredProperties.contains(name)
                val prop = toType(name, propSchema)

                val propL = prop.renamed(_ + "L")
                val propO = prop.renamed(_ + "O")

                if (isRequired)
                  List(
                    q"""val ${propL.pat}: Lens[${modelEntity.typ}, ${prop.typ}] = Lens(obj => Right(obj.${prop.termName}), value => obj => Right(obj.copy(${prop.termName} = value)))"""
                  )
                else
                  List(
                    q"""val ${propL.pat}: Lens[${modelEntity.typ}, ${Types
                      .optional(prop)
                      .typ}] = Lens(obj => Right(obj.${prop.termName}), value => obj => Right(obj.copy(${prop.termName} = value)))""",
                    q"""val ${propO.pat} = ${propL.term} >>> present[${prop.typ}, ${prop.typ}]"""
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
