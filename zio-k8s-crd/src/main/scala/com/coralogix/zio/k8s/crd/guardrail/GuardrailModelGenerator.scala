package com.coralogix.zio.k8s.crd.guardrail

import cats.data.NonEmptyList
import cats.implicits.*
import com.coralogix.zio.k8s.codegen.internal.CodegenIO.*
import dev.guardrail.cli.{ CLI, CLICommon }
import dev.guardrail.core.CoreTermInterp
import dev.guardrail.generators.scala.{ ScalaGeneratorMappings, ScalaLanguage }
import dev.guardrail.terms.CoreTerms
import dev.guardrail.{ Args, CodegenTarget, Context, Target, UnparseableArgument }
import io.circe.*
import io.circe.syntax.*
import zio.ZIO
import zio.nio.file.{ Files, Path }

import java.nio.file.attribute.FileAttribute
import scala.meta.*

object GuardrailModelGenerator {
  class K8sCodegen(implicit k8sContext: K8sCodegenContext) extends CLICommon {
    val AnsiColor = scala.io.AnsiColor

    def putErrLn(string: String): Unit = System.err.println(string)
  }

  def generateModelFiles(
    context: K8sCodegenContext,
    rootPackage: List[String],
    useContextForSubPackage: Boolean,
    outputRoot: Path,
    name: String,
    schemaFragments: (String, Json)*
  ): ZIO[Any, Throwable, List[Path]] = {
    val fullSchema = Json.obj(
      "swagger"     := "2.0",
      "info"        := Json.obj(
        "title"   := context.group,
        "version" := context.version
      ),
      "paths"       := Json.obj(),
      "definitions" := Json.obj(schemaFragments: _*)
    )
    val schemaYaml = yaml
      .Printer(preserveOrder = true, stringStyle = yaml.Printer.StringStyle.DoubleQuoted)
      .pretty(fullSchema)
    for {
      schemaYamlPath <-
        Files.createTempFile(prefix = None, fileAttributes = Iterable.empty[FileAttribute[_]])
      _              <- writeTextFile(schemaYamlPath, schemaYaml)

      codegen          = new K8sCodegen()(context)
      guardrailResult <- codegen
                           .guardrailRunner(
                             Map(
                               "scala" -> NonEmptyList.one(
                                 Args.empty.copy(
                                   kind = CodegenTarget.Models,
                                   packageName = Some(rootPackage),
                                   specPath = Some(schemaYamlPath.toString()),
                                   outputPath = Some(outputRoot.toString),
                                   dtoPackage =
                                     if (useContextForSubPackage) List(name, context.version)
                                     else List.empty,
                                   printHelp = false,
                                   context = Context.empty.copy(
                                     framework = Some("zio-k8s")
                                   ),
                                   defaults = false,
                                   imports = List(
                                     "com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta"
                                   )
                                 )
                               )
                             )
                           )
                           .fold(
                             error =>
                               ZIO.fail(new RuntimeException(s"Guardrail failed with $error")),
                             files => ZIO.succeed(files)
                           )
      generatedFiles   = guardrailResult.map(Path.fromJava)
      _               <- postProcessOptionals(generatedFiles)
    } yield generatedFiles
  }

  private def postProcessOptionals(files: List[Path]): ZIO[Any, Throwable, Unit] =
    ZIO.foreachDiscard(files)(postProcessOptionalsIn)

  private def postProcessOptionalsIn(file: Path): ZIO[Any, Throwable, Unit] =
    for {
      rawSource    <- readTextFile(file)
      ast          <- ZIO.fromEither(rawSource.parse[Source].toEither).mapError(_.details)
      updatedAst    = postProcessOptionalsInAst(ast)
      updatedSource = updatedAst.toString()
      _            <- writeTextFile(file, updatedSource)
    } yield ()

  private def postProcessOptionalsInAst(ast: Source): Source =
    ast
      .transform {
        case Type.Name(name) if name == "Option" => t"zio.prelude.data.Optional"
      }
      .asInstanceOf[Source]
}
