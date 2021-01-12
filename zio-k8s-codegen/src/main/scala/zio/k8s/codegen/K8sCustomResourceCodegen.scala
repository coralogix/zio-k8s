package zio.k8s.codegen

import io.circe.syntax._
import io.circe.yaml.parser._
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio._
import zio.blocking.Blocking
import zio.k8s.codegen.CodegenIO.{format, readTextFile, writeTextFile}
import zio.k8s.codegen.codegen.{ClientModuleGenerator, Conversions}
import zio.k8s.codegen.guardrail._
import zio.k8s.codegen.k8smodel._
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.stream.ZStream

import java.io.File
import java.nio.file.StandardCopyOption

object K8sCustomResourceCodegen extends ClientModuleGenerator {
  private def adjustSchema(schema: JsonSchemaProps): JsonSchemaProps =
    schema.copy(properties = schema.properties.map { props =>
      if (props.contains("metadata"))
        props
      else
        props.updated(
          "metadata",
          JsonSchemaProps(
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
    val entityName = crd.spec.names.singular.getOrElse(crd.spec.names.plural)
    val pluralName = crd.spec.names.plural
    version.schema.flatMap(_.openApiv3Schema) match {
      case Some(originalSchema) =>
        val schema = adjustSchema(originalSchema)
        val schemaFragment = schema.asJson.deepDropNullValues
        val basePackage = (Vector("zio", "k8s", "client") ++ Conversions.groupNameToPackageName(crd.spec.group))
        for {
          generatedModels <- GuardrailModelGenerator.generateModelFiles(
            K8sCodegenContext(
              crd.spec.names.kind,
              crd.spec.group,
              version.name
            ),
            basePackage.toList,
            useContextForSubPackage = true,
            outputRoot,
            entityName,
            entityName -> schemaFragment
          )

          crdModule <- generateCustomResourceModuleCode(crd, version.name, Path("crds") / yamlPath.filename)
          modulePathComponents = (basePackage ++ Vector(pluralName, version.name, "package.scala")).map(s => Path(s))
          modulePath = modulePathComponents.foldLeft(outputRoot)(_ / _)
          _ <- Files.createDirectories(modulePath.parent.get)
          _ <- writeTextFile(modulePath, crdModule)
        } yield modulePath :: generatedModels
      case None =>
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
      paths <- ZStream
                 .fromEffect(generateForResource(yaml, targetDir))
                 .map(Chunk.fromIterable)
                 .flattenChunks
                 .mapMPar(4)(format(scalafmt, _))
                 .runCollect
      _ <- ZIO.effect(log.info(s"Generated from $yaml:\n${paths.mkString("\n")}"))
    } yield paths.map(_.toFile)

  def generateResource(
    yaml: Path,
    targetDir: Path,
    log: Logger
  ): ZIO[Blocking, Throwable, Seq[File]] = {
    val crdResources = targetDir / "crds"
    for {
      _ <- Files.createDirectories(crdResources)
      copiedYaml = crdResources / yaml.filename
      _ <- Files.copy(yaml, copiedYaml, StandardCopyOption.REPLACE_EXISTING)
      _ <- ZIO.effect(log.info(s"Copied CRD to $copiedYaml"))
    } yield Seq(copiedYaml.toFile)
  }
}
