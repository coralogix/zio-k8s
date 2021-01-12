package com.coralogix.zio.k8s.codegen

import io.swagger.parser.OpenAPIParser
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.parser.core.models.ParseOptions
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.blocking.Blocking
import com.coralogix.zio.k8s.codegen.internal._
import com.coralogix.zio.k8s.codegen.internal.Conversions._
import com.coralogix.zio.k8s.codegen.internal.CodegenIO._
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.{ Task, ZIO }

import java.io.File
import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

object K8sResourceCodegen extends ModelGenerator with ClientModuleGenerator {

  def generateAll(log: Logger, from: Path, targetDir: Path): ZIO[Blocking, Throwable, Seq[File]] =
    for {
      // Loading
      spec     <- loadK8sSwagger(log, from)
      scalafmt <- ZIO.effect(Scalafmt.create(this.getClass.getClassLoader))

      // Identifying
      definitions = spec.getComponents.getSchemas.asScala
                      .flatMap((IdentifiedSchema.identifyDefinition _).tupled)
                      .toSet
      definitionMap = definitions.map(d => d.name -> d).toMap

      paths      = spec.getPaths.asScala.flatMap((IdentifiedPath.identifyPath _).tupled)
      identified = paths.collect { case i: IdentifiedAction => i }

      // Classifying
      resources = ClassifiedResource.classifyActions(definitionMap, identified)

      // Generating code
      packagePaths <- generateAllPackages(scalafmt, log, targetDir, definitionMap, resources)
      modelPaths   <- generateAllModels(scalafmt, log, targetDir, definitions)
    } yield (packagePaths union modelPaths).map(_.toFile).toSeq

  private def loadK8sSwagger(log: Logger, from: Path): ZIO[Blocking, Throwable, OpenAPI] =
    Task.effect(log.info("Loading k8s-swagger.json")) *>
      Files.readAllBytes(from).flatMap { bytes =>
        Task.effect {
          val rawJson = new String(bytes.toArray[Byte], StandardCharsets.UTF_8)

          val parser = new OpenAPIParser
          val opts = new ParseOptions()
          opts.setResolve(true)
          val parserResult = parser.readContents(rawJson, List.empty.asJava, opts)

          Option(parserResult.getMessages).foreach { messages =>
            messages.asScala.foreach(println)
          }

          Option(parserResult.getOpenAPI) match {
            case Some(spec) => spec
            case None       => throw new RuntimeException(s"Failed to parse k8s swagger specs")
          }
        }
      }

  private val clientRoot = Vector("com", "coralogix", "zio", "k8s", "client")

  def generateAllPackages(
    scalafmt: Scalafmt,
    log: Logger,
    targetRoot: Path,
    definitionMap: Map[String, IdentifiedSchema],
    resources: Set[SupportedResource]
  ): ZIO[Blocking, Throwable, Set[Path]] =
    ZIO.foreach(resources) { resource =>
      generatePackage(scalafmt, log, targetRoot, definitionMap, resource)
    }

  private def generatePackage(
    scalafmt: Scalafmt,
    log: Logger,
    targetRoot: Path,
    definitionMap: Map[String, IdentifiedSchema],
    resource: SupportedResource
  ) =
    for {
      _ <- ZIO.effect(log.info(s"Generating package code for ${resource.id}"))

      groupName = groupNameToPackageName(resource.group)
      pkg       = (clientRoot ++ groupName) :+ resource.plural :+ resource.version

      (entityPkg, entity) = splitName(resource.modelName)

      src <- generateModuleCode(
               basePackageName = clientRoot.mkString("."),
               modelPackageName = "com.coralogix.zio.k8s.model." + entityPkg.mkString("."),
               name = resource.plural,
               entity = entity,
               statusEntity = findStatusEntity(definitionMap, resource.modelName).map(s =>
                 s"com.coralogix.zio.k8s.model.$s"
               ),
               group = resource.group,
               kind = resource.kind,
               version = resource.version,
               isNamespaced = resource.namespaced,
               None
             )
      targetDir = pkg.foldLeft(targetRoot)(_ / _)
      _ <- Files.createDirectories(targetDir)
      targetPath = targetDir / "package.scala"
      _ <- writeTextFile(targetPath, src)
      _ <- format(scalafmt, targetPath)
    } yield targetPath

  protected def findStatusEntity(
    definitions: Map[String, IdentifiedSchema],
    modelName: String
  ): Option[String] = {
    val modelSchema = definitions(modelName).schema.asInstanceOf[ObjectSchema]
    for {
      properties       <- Option(modelSchema.getProperties)
      statusPropSchema <- Option(properties.get("status"))
      ref              <- Option(statusPropSchema.get$ref())
      (pkg, name) = splitName(ref.drop("#/components/schemas/".length))
    } yield pkg.mkString(".") + "." + name
  }

}
