package com.coralogix.zio.k8s.codegen

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.*
import com.coralogix.zio.k8s.codegen.internal.Conversions.*
import com.coralogix.zio.k8s.codegen.internal.*
import io.github.vigoo.metagen.core.{ Generator, GeneratorFailure }
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.core.models.ParseOptions
import org.scalafmt.interfaces.Scalafmt
import zio.nio.file.Path
import zio.nio.file.Files
import zio.{ Task, ZIO }
import zio.ZIO.*

import java.io.File
import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters.*

class K8sResourceCodegen(val logger: sbt.Logger, val scalaVersion: String)
    extends Common with ModelGenerator with ClientModuleGenerator with MonocleOpticsGenerator
    with SubresourceClientGenerator with UnifiedClientModuleGenerator with ZioOpticsGenerator {

  def generateAll(from: Path, targetDir: Path): ZIO[Any, Throwable, Seq[File]] =
    for {
      // Loading
      spec     <- loadK8sSwagger(from)
      scalafmt <- ZIO.attempt(Scalafmt.create(this.getClass.getClassLoader))

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
      subresourceIds    = subresources.map(_.id)
      subresourcePaths <- generateSubresourceAliases(scalafmt, targetDir, subresourceIds)

      // Generating code
      packagePaths <- generateAllPackages(scalafmt, targetDir, definitionMap, resources)
      modelPaths   <- generateAllModels(scalafmt, targetDir, definitionMap, resources)
      unifiedPaths <- generateUnifiedClientModule(
                        scalafmt,
                        targetDir,
                        clientRoot.mkString("."),
                        definitionMap,
                        resources
                      )
    } yield (packagePaths union modelPaths union subresourcePaths union unifiedPaths)
      .map(_.toFile)
      .toSeq

  def generateAllMonocle(
    from: Path
  ): ZIO[Generator, GeneratorFailure[Throwable], Seq[File]] =
    for {
      // Loading
      spec <- loadK8sSwagger(from).mapError(GeneratorFailure.CustomFailure(_))

      // Identifying
      definitions  = spec.getComponents.getSchemas.asScala
                       .flatMap((IdentifiedSchema.identifyDefinition _).tupled)
                       .toSet

      // Generating code
      opticsPaths <- generateAllMonocleOptics(definitions)
    } yield opticsPaths.map(_.toFile).toSeq

  def generateAllOptics(
    from: Path
  ): ZIO[Generator, GeneratorFailure[Throwable], Seq[File]] =
    for {
      // Loading
      spec <- loadK8sSwagger(from).mapError(GeneratorFailure.CustomFailure(_))

      // Identifying
      definitions  = spec.getComponents.getSchemas.asScala
                       .flatMap((IdentifiedSchema.identifyDefinition _).tupled)
                       .toSet

      // Generating code
      opticsPaths <- generateAllZioOptics(definitions)
    } yield opticsPaths.map(_.toFile).toSeq

  private def loadK8sSwagger(from: Path): ZIO[Any, Throwable, OpenAPI] =
    ZIO.attempt(logger.info("Loading k8s-swagger.json")) *>
      Files.readAllBytes(from).flatMap { bytes =>
        ZIO.attempt {
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
  ): ZIO[Any, Throwable, Set[Path]] =
    ZIO.foreach(resources) { resource =>
      generatePackage(scalafmt, targetRoot, definitionMap, resource)
    }

  private def generatePackage(
    scalafmt: Scalafmt,
    targetRoot: Path,
    definitionMap: Map[String, IdentifiedSchema],
    resource: SupportedResource
  ): ZIO[Any, Throwable, Path] =
    for {
      _ <- ZIO.attempt(logger.info(s"Generating package code for ${resource.id}"))

      groupName = groupNameToPackageName(resource.gvk.group)
      pkg       = (clientRoot ++ groupName) :+ resource.gvk.version :+ resource.plural

      (entityPkg, entity) = splitNameOld(resource.modelName)
      deleteResponse      = resource.actions
                              .map(_.endpointType)
                              .collectFirst { case EndpointType.Delete(_, _, responseTypeRef) =>
                                s"com.coralogix.zio.k8s.model.$responseTypeRef"
                              }
                              .getOrElse("com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status")

      src       <- generateModuleCode(
                     basePackageName = clientRoot.mkString("."),
                     modelPackageName = "com.coralogix.zio.k8s.model." + entityPkg.mkString("."),
                     name = resource.plural,
                     entity = entity,
                     statusEntity = findStatusEntity(definitionMap, resource.modelName).map(s =>
                       s"com.coralogix.zio.k8s.model.$s"
                     ),
                     deleteResponse = deleteResponse,
                     gvk = resource.gvk,
                     isNamespaced = resource.namespaced,
                     subresources = resource.subresources.map(_.id),
                     None,
                     resource.supportsDeleteMany
                   )
      targetDir  = pkg.foldLeft(targetRoot)(_ / _)
      _         <- Files.createDirectories(targetDir)
      targetPath = targetDir / "package.scala"
      _         <- writeTextFile(targetPath, src)
      _         <- format(scalafmt, targetPath)
    } yield targetPath

  private def checkUnidentifiedPaths(paths: Seq[IdentifiedPath]): Task[Unit] =
    for {
      whitelistInfo <- ZIO.foreach(paths) { path =>
                         Whitelist.isWhitelistedPath(path) match {
                           case s @ Some(_) => ZIO.succeed(s)
                           case None        =>
                             ZIO
                               .attempt(
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
      _             <- ZIO.attempt(logger.info(s"Issues for currently unsupported paths:"))
      _             <- ZIO.foreachDiscard(issues) { issue =>
                         ZIO.attempt(logger.info(s" - ${issue.url}"))
                       }
    } yield ()
}
