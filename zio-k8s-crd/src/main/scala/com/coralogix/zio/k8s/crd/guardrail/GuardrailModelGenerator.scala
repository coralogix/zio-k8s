package com.coralogix.zio.k8s.crd.guardrail

import cats.data.NonEmptyList
import cats.implicits.*
import com.twilio.guardrail.core.{ CoreTermInterp, StructuredLogger }
import com.twilio.guardrail.generators.ScalaModule
import com.twilio.guardrail.languages.{ JavaLanguage, ScalaLanguage }
import com.twilio.guardrail.terms.CoreTerms
import com.twilio.guardrail.{
  Args,
  CLI,
  CLICommon,
  CodegenTarget,
  Context,
  ReadSwagger,
  Target,
  UnparseableArgument,
  WriteTree
}
import io.circe.*
import io.circe.syntax.*
import io.circe.yaml.parser.*
import org.scalafmt.interfaces.Scalafmt
import zio.{ Chunk, ZIO }
import zio.blocking.Blocking
import zio.nio.file.Path
import zio.nio.file.Files
import zio.stream.{ Transducer, ZStream }

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileAttribute
import scala.meta.*
import com.coralogix.zio.k8s.codegen.internal.CodegenIO.*

import scala.io.AnsiColor

object GuardrailModelGenerator {
  class K8sCodegen(implicit k8sContext: K8sCodegenContext) extends CLICommon {
    override implicit def scalaInterpreter: CoreTerms[ScalaLanguage, Target] =
      new CoreTermInterp[ScalaLanguage](
        "zio-k8s",
        ScalaModule.extract,
        { case "zio-k8s" =>
          new ZioK8s
        },
        _.parse[Importer].toEither.bimap(
          err => UnparseableArgument("import", err.toString),
          importer => Import(List(importer))
        )
      )

    override implicit def javaInterpreter: CoreTerms[JavaLanguage, Target] =
      CLI.javaInterpreter

    override def guardrailRunner
      : Map[String, NonEmptyList[Args]] => Target[List[java.nio.file.Path]] = { tasks =>
      runLanguages(tasks)
        .flatMap(
          _.flatTraverse(rs =>
            ReadSwaggerImpl
              .readSwagger(rs)
              .flatMap(_.traverse(WriteTree.writeTree))
              .leftFlatMap(value =>
                Target.pushLogger(
                  StructuredLogger.error(s"${AnsiColor.RED}Error in ${rs.path}${AnsiColor.RESET}")
                ) *> Target.raiseError[List[java.nio.file.Path]](value)
              )
              .productL(Target.pushLogger(StructuredLogger.reset))
          )
        )
        .map(_.distinct)
    }
  }

  def generateModelFiles(
    context: K8sCodegenContext,
    rootPackage: List[String],
    useContextForSubPackage: Boolean,
    outputRoot: Path,
    name: String,
    schemaFragments: (String, Json)*
  ): ZIO[Blocking, Throwable, List[Path]] = {
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

  private def postProcessOptionals(files: List[Path]): ZIO[Blocking, Throwable, Unit] =
    ZIO.foreach_(files)(postProcessOptionalsIn)

  private def postProcessOptionalsIn(file: Path): ZIO[Blocking, Throwable, Unit] =
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
        case Type.Name(name) if name == "Option" => t"com.coralogix.zio.k8s.client.model.Optional"
      }
      .asInstanceOf[Source]
}
