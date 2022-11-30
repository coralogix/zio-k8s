package com.coralogix.zio.k8s.codegen

import com.coralogix.zio.k8s.codegen.internal.CodegenIO._
import com.coralogix.zio.k8s.codegen.internal.Conversions._
import com.coralogix.zio.k8s.codegen.internal._
import io.github.vigoo.metagen.core.{ Generator, GeneratorFailure, Package }
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.core.models.ParseOptions
import zio.blocking.Blocking
import zio.nio.file.{ Files, Path }
import zio.{ Has, Task, ZIO }

import java.io.File
import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

class K8sResourceCodegen(val logger: sbt.Logger, val scalaVersion: String)
    extends Common with ModelGenerator with ClientModuleGenerator with MonocleOpticsGenerator
    with SubresourceClientGenerator with UnifiedClientModuleGenerator with ZioOpticsGenerator {

  def generateAll(
    from: Path
  ): ZIO[Has[Generator] with Blocking, GeneratorFailure[Throwable], Seq[File]] =
    for {
      // Loading
      spec <- loadK8sSwagger(from)

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
      subresourcePaths <- generateSubresourceAliases(subresourceIds)

      // Generating code
      packagePaths <- generateAllPackages(definitionMap, resources)
      modelPaths   <- generateAllModels(definitionMap, resources)
      unifiedPaths <- generateUnifiedClientModule(
                        clientRoot.asString,
                        definitionMap,
                        resources
                      )
    } yield (packagePaths union modelPaths union subresourcePaths union unifiedPaths)
      .map(_.toFile)
      .toSeq

  def generateAllMonocle(
    from: Path
  ): ZIO[Has[Generator] with Blocking, GeneratorFailure[Throwable], Seq[File]] =
    for {
      // Loading
      spec <- loadK8sSwagger(from)

      // Identifying
      definitions  = spec.getComponents.getSchemas.asScala
                       .flatMap((IdentifiedSchema.identifyDefinition _).tupled)
                       .toSet

      // Generating code
      opticsPaths <- generateAllMonocleOptics(definitions)
    } yield opticsPaths.map(_.toFile).toSeq

  def generateAllOptics(
    from: Path
  ): ZIO[Has[Generator] with Blocking, GeneratorFailure[Throwable], Seq[File]] =
    for {
      // Loading
      spec <- loadK8sSwagger(from)

      // Identifying
      definitions  = spec.getComponents.getSchemas.asScala
                       .flatMap((IdentifiedSchema.identifyDefinition _).tupled)
                       .toSet

      // Generating code
      opticsPaths <- generateAllZioOptics(definitions)
    } yield opticsPaths.map(_.toFile).toSeq

  private def loadK8sSwagger(from: Path): ZIO[Blocking, GeneratorFailure[Throwable], OpenAPI] =
    Task.effectTotal(logger.info("Loading k8s-swagger.json")) *>
      Files
        .readAllBytes(from)
        .flatMap { bytes =>
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
        .mapError(GeneratorFailure.CustomFailure(_))

  private val clientRoot = Package("com", "coralogix", "zio", "k8s", "client")

  def generateAllPackages(
    definitionMap: Map[String, IdentifiedSchema],
    resources: Set[SupportedResource]
  ): ZIO[Has[Generator] with Blocking, GeneratorFailure[Throwable], Set[Path]] =
    ZIO.foreach(resources) { resource =>
      generatePackage(definitionMap, resource)
    }

  private def generatePackage(
    definitionMap: Map[String, IdentifiedSchema],
    resource: SupportedResource
  ): ZIO[Has[Generator] with Blocking, GeneratorFailure[Throwable], Path] =
    for {
      _ <- ZIO.effectTotal(logger.info(s"Generating package code for ${resource.id}"))

      groupName = groupNameToPackageName(resource.gvk.group)
      pkg       = clientRoot / groupName / resource.gvk.version

      deleteResponse = resource.actions
                         .map(_.endpointType)
                         .collectFirst { case EndpointType.Delete(_, _, responseTypeRef) =>
                           responseTypeRef
                         }
                         .getOrElse(Types.status)

      targetPath <- Generator.generateScalaPackageObject[Any, Throwable](pkg, resource.plural) {
                      generateModuleCode(
                        pkg = pkg,
                        name = resource.plural,
                        entity = resource.model,
                        statusEntity =
                          findStatusEntity(Packages.k8sModel, definitionMap, resource.schemaName),
                        deleteResponse = deleteResponse,
                        gvk = resource.gvk,
                        isNamespaced = resource.namespaced,
                        subresources = resource.subresources.map(_.id),
                        None,
                        resource.supportsDeleteMany
                      )
                    }
    } yield targetPath

  private def checkUnidentifiedPaths(
    paths: Seq[IdentifiedPath]
  ): ZIO[Any, GeneratorFailure[Throwable], Unit] =
    for {
      whitelistInfo <- ZIO.foreach(paths) { path =>
                         Whitelist.isWhitelistedPath(path) match {
                           case s @ Some(_) => ZIO.succeed(s)
                           case None        =>
                             ZIO
                               .effectTotal(
                                 logger.error(s"Unsupported, non-whitelisted path: ${path.name}")
                               )
                               .as(None)
                         }
                       }
      issues         = whitelistInfo.collect { case Some(issueRef) => issueRef }.toSet
      _             <- ZIO
                         .fail(
                           GeneratorFailure.CustomFailure(
                             new sbt.MessageOnlyException(
                               "Unknown, non-whitelisted path found. See the code generation log."
                             )
                           )
                         )
                         .when(whitelistInfo.contains(None))
      _             <- ZIO.effectTotal(logger.info(s"Issues for currently unsupported paths:"))
      _             <- ZIO.foreach_(issues) { issue =>
                         ZIO.effectTotal(logger.info(s" - ${issue.url}"))
                       }
    } yield ()
}
