package com.coralogix.zio.k8s.crd

import com.coralogix.zio.k8s.crd.guardrail._
import com.coralogix.zio.k8s.codegen.internal._
import com.coralogix.zio.k8s.codegen.internal.CodegenIO._
import com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1._
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1
import com.twilio.guardrail.generators.syntax._
import io.circe.syntax._
import io.circe.yaml.parser.parse
import sbt._
import sbt.util.Logger
import org.scalafmt.interfaces.Scalafmt
import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.stream.ZStream
import zio.{ Chunk, Task, ZIO }

import java.io.File
import java.nio.file.StandardCopyOption

class K8sCustomResourceCodegen(val scalaVersion: String) extends Common with ClientModuleGenerator {
  def generateCustomResourceModuleCode(
    crd: CustomResourceDefinition,
    version: String,
    yamlPath: Path
  ): Task[String] = {
    val singular = crd.spec.names.singular.getOrElse(crd.spec.names.plural)
    val entityName = crd.spec.names.kind
    val moduleName = crd.spec.names.plural
    generateModuleCode(
      "com.coralogix.zio.k8s.client",
      if (crd.spec.group.nonEmpty) {
        val groupPart = Conversions.groupNameToPackageName(crd.spec.group).mkString(".")
        s"com.coralogix.zio.k8s.client.$groupPart.definitions.$singular.$version"
      } else
        s"com.coralogix.zio.k8s.client.definitions.$singular.$version",
      moduleName,
      entityName.toPascalCase,
      crd.spec.versions
        .find(_.name == version)
        .flatMap(_.subresources.flatMap(_.status).toOption)
        .map(_ => entityName.toPascalCase + ".Status"),
      "com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status",
      GroupVersionKind(
        crd.spec.group,
        version,
        crd.spec.names.kind
      ),
      crd.spec.scope == "Namespaced",
      subresources = crd.spec.versions
        .find(_.name == version)
        .flatMap(_.subresources.flatMap(_.scale).toOption)
        .toSet
        .map { (_: CustomResourceSubresourceScale) =>
          SubresourceId(
            "scale",
            "io.k8s.api.autoscaling.v1.Scale",
            Set("get", "patch", "put"),
            Map.empty
          )
        },
      Some(yamlPath),
      true
    )
  }

  private def adjustSchema(schema: JSONSchemaProps): JSONSchemaProps =
    schema.copy(properties = schema.properties.map { props =>
      props.updated(
        "metadata",
        JSONSchemaProps(
          $ref = Some("ObjectMeta")
        )
      )
    })

  // TODO: configurable root package
  private def generateForVersion(
    crd: CustomResourceDefinition,
    version: CustomResourceDefinitionVersion,
    yamlPath: Path,
    outputRoot: Path
  ): ZIO[Blocking, Throwable, List[Path]] = {
    val singular = crd.spec.names.singular.getOrElse(crd.spec.names.plural)
    val entityName = crd.spec.names.kind
    val pluralName = crd.spec.names.plural
    version.schema.flatMap(_.openAPIV3Schema).toOption match {
      case Some(originalSchema) =>
        val schema = adjustSchema(originalSchema)
        val hasStatus = version.subresources.map(_.status.isDefined).getOrElse(false)
        val isMetadataOptional = !schema.required.map(_.contains("metadata")).getOrElse(false)

        val schemaFragment = schema.asJson.deepDropNullValues
        val basePackage =
          (Vector("com", "coralogix", "zio", "k8s", "client") ++ Conversions.groupNameToPackageName(
            crd.spec.group
          ))
        for {
          generatedModels <- GuardrailModelGenerator.generateModelFiles(
                               K8sCodegenContext(
                                 crd.spec.names.kind,
                                 crd.spec.group,
                                 version.name,
                                 pluralName,
                                 hasStatus,
                                 isMetadataOptional
                               ),
                               basePackage.toList,
                               useContextForSubPackage = true,
                               outputRoot,
                               singular,
                               entityName -> schemaFragment
                             )

          crdModule           <-
            generateCustomResourceModuleCode(crd, version.name, Path("crds") / yamlPath.filename)
          modulePathComponents =
            (basePackage ++ Vector(pluralName, version.name, "package.scala")).map(s => Path(s))
          modulePath           = modulePathComponents.foldLeft(outputRoot)(_ / _)
          _                   <- Files.createDirectories(modulePath.parent.get)
          _                   <- writeTextFile(modulePath, crdModule)
        } yield modulePath :: generatedModels
      case None                 =>
        ZIO.succeed(List.empty)
    }
  }

  private def generateForResource(
    path: Path,
    targetDir: Path
  ): ZIO[Blocking, Throwable, Set[Path]] =
    for {
      yaml   <- readTextFile(path)
      rawCrd <- ZIO.fromEither(parse(yaml))
      crd    <- ZIO.fromEither(rawCrd.as[CustomResourceDefinition])
      paths  <- ZIO.foreachPar(crd.spec.versions.toSet)(generateForVersion(crd, _, path, targetDir))
    } yield paths.flatten

  def generateSource(
    yaml: Path,
    targetDir: Path,
    log: Logger
  ): ZIO[Blocking, Throwable, Seq[File]] =
    for {
      scalafmt <- ZIO.effect(Scalafmt.create(this.getClass.getClassLoader))
      paths    <- ZStream
                    .fromEffect(generateForResource(yaml, targetDir))
                    .map(Chunk.fromIterable)
                    .flattenChunks
                    .mapMPar(4)(format(scalafmt, _))
                    .runCollect
      _        <- ZIO.effect(log.info(s"Generated from $yaml:\n${paths.mkString("\n")}"))
    } yield paths.map(_.toFile)

  def generateResource(
    yaml: Path,
    targetDir: Path,
    log: Logger
  ): ZIO[Blocking, Throwable, Seq[File]] = {
    val crdResources = targetDir / "crds"
    for {
      _         <- Files.createDirectories(crdResources)
      copiedYaml = crdResources / yaml.filename
      _         <- Files.copy(yaml, copiedYaml, StandardCopyOption.REPLACE_EXISTING)
      _         <- ZIO.effect(log.info(s"Copied CRD to $copiedYaml"))
    } yield Seq(copiedYaml.toFile)
  }
}
