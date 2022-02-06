package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.writeTextFile
import com.coralogix.zio.k8s.codegen.internal.Conversions.splitName
import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import zio.ZIO
import zio.ZIO._
import zio.nio.file.Path
import zio.nio.file.Files

import scala.collection.JavaConverters._

trait ZioOpticsGenerator {
  this: Common with ModelGenerator =>

  private val opticsRoot = Vector("com", "coralogix", "zio", "k8s", "optics")

  protected def generateAllZioOptics(
    scalafmt: Scalafmt,
    targetRoot: Path,
    definitions: Set[IdentifiedSchema]
  ): ZIO[Any, Throwable, Set[Path]] = {
    val filteredDefinitions = definitions.filter(d => !isListModel(d))
    for {
      _     <-
        ZIO.attempt(
          logger.info(s"Generating ZIO Optics for ${filteredDefinitions.size} models...")
        )
      paths <- ZIO.foreach(filteredDefinitions) { d =>
                 val (groupName, entityName) = splitName(d.name)
                 val pkg = (opticsRoot ++ groupName)
                 val modelPkg = (modelRoot ++ groupName)

                 for {
                   _         <- ZIO.attempt(logger.info(s"Generating '$entityName' to ${pkg.mkString(".")}"))
                   src        =
                     generateZioOptics(modelRoot, pkg, modelPkg, entityName, d)
                   targetDir  = pkg.foldLeft(targetRoot)(_ / _)
                   _         <- Files.createDirectories(targetDir)
                   targetPath = targetDir / s"$entityName.scala"
                   _         <- writeTextFile(targetPath, src)
                   _         <- format(scalafmt, targetPath)
                 } yield targetPath
               }
    } yield paths
  }

  private def generateZioOptics(
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
                    q"""val $nameLP: Lens[$entityNameT, $propT] = Lens(obj => Right(obj.$nameN), value => obj => Right(obj.copy($nameN = value)))"""
                  )
                else
                  List(
                    q"""val $nameLP: Lens[$entityNameT, Optional[$propT]] = Lens(obj => Right(obj.$nameN), value => obj => Right(obj.copy($nameN = value)))""",
                    q"""val $nameOP = $nameLN >>> present[$propT, $propT]"""
                  )
              }
          case _                => List.empty
        }
      case _              => List.empty
    }

    val tree =
      q"""package $packageTerm {

          import java.time.OffsetDateTime
          import zio.Chunk
          import zio.optics._

          import com.coralogix.zio.k8s.client.model.Optional
          import com.coralogix.zio.k8s.optics.{absent, present}

          import $modelRootPackageTerm._
          import $modelPackageTerm._

          object $entityOpticsN {
            ..$optics
          }
          }
      """
    prettyPrint(tree)
  }
}
