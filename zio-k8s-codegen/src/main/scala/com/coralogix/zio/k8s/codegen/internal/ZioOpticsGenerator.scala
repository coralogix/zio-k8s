package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.writeTextFile
import com.coralogix.zio.k8s.codegen.internal.Conversions.{ modelRoot, splitName }
import io.github.vigoo.metagen.core._
import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import zio.ZIO
import zio.blocking.Blocking
import zio.nio.file.Path
import zio.nio.file.Files

import scala.collection.JavaConverters._

trait ZioOpticsGenerator {
  this: Common with ModelGenerator =>

  private val opticsRoot = Package("com", "coralogix", "zio", "k8s", "optics")

  protected def generateAllZioOptics(
    scalafmt: Scalafmt,
    targetRoot: Path,
    definitions: Set[IdentifiedSchema]
  ): ZIO[Blocking, Throwable, Set[Path]] = {
    val filteredDefinitions = definitions.filter(d => !isListModel(d))
    for {
      _     <-
        ZIO.effect(
          logger.info(s"Generating ZIO Optics for ${filteredDefinitions.size} models...")
        )
      paths <- ZIO.foreach(filteredDefinitions) { d =>
                 val modelEntity = splitName(d.name, modelRoot)
                 val opticsEntity = splitName(d.name, opticsRoot)

                 for {
                   _         <-
                     ZIO.effect(
                       logger.info(s"Generating '${opticsEntity.name}' to ${opticsEntity.pkg.show}")
                     )
                   src        =
                     generateZioOptics(modelEntity, opticsEntity, d)
                   targetDir  = opticsEntity.pkg.asPath
                   _         <- Files.createDirectories(targetDir)
                   targetPath = targetDir / s"${opticsEntity.name}.scala"
                   _         <- writeTextFile(targetPath, src)
                   _         <- format(scalafmt, targetPath)
                 } yield targetPath
               }
    } yield paths
  }

  private def generateZioOptics(
    modelEntity: ScalaType,
    opticsEntity: ScalaType,
    d: IdentifiedSchema
  ): String = {
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

    val tree =
      q"""
          object ${opticsEntity.termName} {
            ..$optics
          }          
      """
    prettyPrint(tree)
  }
}
