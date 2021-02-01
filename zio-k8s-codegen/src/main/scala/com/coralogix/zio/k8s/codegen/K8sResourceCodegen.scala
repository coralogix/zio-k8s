package com.coralogix.zio.k8s.codegen

import com.coralogix.zio.k8s.codegen.internal.CodegenIO._
import com.coralogix.zio.k8s.codegen.internal.Conversions._
import com.coralogix.zio.k8s.codegen.internal._
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.parser.core.models.ParseOptions
import org.scalafmt.interfaces.Scalafmt
import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.{ Task, ZIO }

import java.io.File
import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

class K8sResourceCodegen(val logger: sbt.Logger)
    extends ModelGenerator with ClientModuleGenerator with MonocleOpticsGenerator
    with SubresourceClientGenerator {

  def generateAll(from: Path, targetDir: Path): ZIO[Blocking, Throwable, Seq[File]] =
    for {
      // Loading
      spec     <- loadK8sSwagger(from)
      scalafmt <- ZIO.effect(Scalafmt.create(this.getClass.getClassLoader))

      // Identifying
      definitions   = spec.getComponents.getSchemas.asScala
                        .flatMap((IdentifiedSchema.identifyDefinition _).tupled)
                        .toSet
      definitionMap = definitions.map(d => d.name -> d).toMap

      paths        = spec.getPaths.asScala.flatMap((IdentifiedPath.identifyPath _).tupled).toList
      identified   = paths.collect { case i: IdentifiedAction => i }
      unidentified = paths.filter {
                       case _: IdentifiedAction => false
                       case _                   => true
                     }
      _           <- checkUnidentifiedPaths(unidentified)

      // Classifying
      resources        <- ClassifiedResource.classifyActions(logger, definitionMap, identified.toSet)
      subresources      = resources.flatMap(_.subresources)
      subresourcePaths <- generateSubresourceAliases(scalafmt, targetDir, subresources)

      // Generating code
      packagePaths <- generateAllPackages(scalafmt, targetDir, definitionMap, resources)
      modelPaths   <- generateAllModels(scalafmt, targetDir, definitions, resources)
    } yield (packagePaths union modelPaths union subresourcePaths).map(_.toFile).toSeq

  def generateAllMonocle(
    from: Path,
    targetDir: Path
  ): ZIO[Blocking, Throwable, Seq[File]] =
    for {
      // Loading
      spec     <- loadK8sSwagger(from)
      scalafmt <- ZIO.effect(Scalafmt.create(this.getClass.getClassLoader))

      // Identifying
      definitions  = spec.getComponents.getSchemas.asScala
                       .flatMap((IdentifiedSchema.identifyDefinition _).tupled)
                       .toSet

      // Generating code
      opticsPaths <- generateAllOptics(scalafmt, targetDir, definitions)
    } yield opticsPaths.map(_.toFile).toSeq

  private def loadK8sSwagger(from: Path): ZIO[Blocking, Throwable, OpenAPI] =
    Task.effect(logger.info("Loading k8s-swagger.json")) *>
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
    targetRoot: Path,
    definitionMap: Map[String, IdentifiedSchema],
    resources: Set[SupportedResource]
  ): ZIO[Blocking, Throwable, Set[Path]] =
    ZIO.foreach(resources) { resource =>
      generatePackage(scalafmt, targetRoot, definitionMap, resource)
    }

  private def generatePackage(
    scalafmt: Scalafmt,
    targetRoot: Path,
    definitionMap: Map[String, IdentifiedSchema],
    resource: SupportedResource
  ) =
    for {
      _ <- ZIO.effect(logger.info(s"Generating package code for ${resource.id}"))

      groupName = groupNameToPackageName(resource.gvk.group)
      pkg       = (clientRoot ++ groupName) :+ resource.plural :+ resource.gvk.version

      (entityPkg, entity) = splitName(resource.modelName)

      src       <- generateModuleCode(
                     basePackageName = clientRoot.mkString("."),
                     modelPackageName = "com.coralogix.zio.k8s.model." + entityPkg.mkString("."),
                     name = resource.plural,
                     entity = entity,
                     statusEntity = findStatusEntity(definitionMap, resource.modelName).map(s =>
                       s"com.coralogix.zio.k8s.model.$s"
                     ),
                     gvk = resource.gvk,
                     isNamespaced = resource.namespaced,
                     subresources = resource.subresources,
                     None
                   )
      targetDir  = pkg.foldLeft(targetRoot)(_ / _)
      _         <- Files.createDirectories(targetDir)
      targetPath = targetDir / "package.scala"
      _         <- writeTextFile(targetPath, src)
      _         <- format(scalafmt, targetPath)
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
      (pkg, name)       = splitName(ref.drop("#/components/schemas/".length))
    } yield pkg.mkString(".") + "." + name
  }

  private def checkUnidentifiedPaths(paths: Seq[IdentifiedPath]): Task[Unit] =
    for {
      whitelistInfo <- ZIO.foreach(paths) { path =>
                         Whitelist.isWhitelistedPath(path) match {
                           case s @ Some(_) => ZIO.succeed(s)
                           case None        =>
                             ZIO
                               .effect(
                                 logger.error(s"Unsupported, non-whitelisted path: ${path.name}")
                               )
                               .as(None)
                         }
                       }
      issues         = whitelistInfo.collect { case Some(issueRef) => issueRef }.toSet
      _             <- ZIO
                         .fail(
                           new sbt.MessageOnlyException(
                             "Unknown, non-whitelisted path found. See the code generation log."
                           )
                         )
                         .when(whitelistInfo.contains(None))
      _             <- ZIO.effect(logger.info(s"Issues for currently unsupported paths:"))
      _             <- ZIO.foreach_(issues) { issue =>
                         ZIO.effect(logger.info(s" - ${issue.url}"))
                       }
    } yield ()
}
