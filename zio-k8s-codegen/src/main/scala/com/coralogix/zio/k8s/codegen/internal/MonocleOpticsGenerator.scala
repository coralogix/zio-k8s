package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.writeTextFile
import com.coralogix.zio.k8s.codegen.internal.Conversions.{ modelRoot, splitName }
import io.github.vigoo.metagen.core
import io.github.vigoo.metagen.core._
import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.{ Has, ZIO }
import zio.blocking.Blocking
import zio.nio.file.Path
import zio.nio.file.Files

import scala.collection.JavaConverters._
import scala.meta.Term

trait MonocleOpticsGenerator {
  this: Common with ModelGenerator =>

  private val monocleRoot = Package("com", "coralogix", "zio", "k8s", "monocle")

  protected def generateAllMonocleOptics(
    definitions: Set[IdentifiedSchema]
  ): ZIO[Has[Generator] with Blocking, GeneratorFailure[Nothing], Set[Path]] = {
    val filteredDefinitions = definitions.filter(d => !isListModel(d))
    for {
      _     <-
        ZIO.effectTotal(
          logger.info(s"Generating Monocle optics for ${filteredDefinitions.size} models...")
        )
      paths <- ZIO.foreach(filteredDefinitions) { d =>
                 val model = splitName(d.name)
                 val monocle = splitName(model.name, monocleRoot)

                 for {
                   _          <-
                     ZIO.effectTotal(
                       logger.info(s"Generating '${model.name}' to ${monocle.pkg.show}")
                     )
                   targetPath <-
                     Generator.generateScalaPackage[Any, Nothing](monocle.pkg, model.name)(
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
  ): ZIO[Has[CodeFileGenerator], Nothing, Term.Block] = {
    import scala.meta._

    val opticsModel = ScalaType(pkg, model.name + "O")

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
                    q"""val $nameLP: Lens[${model.typ}, ${prop.typ}] = GenLens[${model.typ}](_.$nameN)"""
                  )
                else
                  List(
                    q"""val $nameLP: Lens[${model.typ}, ${Types
                      .optional(prop)
                      .typ}] = GenLens[${model.typ}](_.$nameN)""",
                    q"""val $nameOP: MonocleOptional[${model.typ}, ${prop.typ}] = optional($nameLN)"""
                  )
              }
          case _                => List.empty
        }
      case _              => List.empty
    }

    ZIO.succeed {
      Term.Block(List(q"""object ${opticsModel.termName} {
            ..$optics
          }
      """))
    }
  }
}
