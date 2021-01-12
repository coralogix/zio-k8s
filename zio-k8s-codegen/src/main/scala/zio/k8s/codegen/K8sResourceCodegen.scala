package zio.k8s.codegen

import io.swagger.parser.OpenAPIParser
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.core.models.ParseOptions
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.{Task, ZIO}
import zio.blocking.Blocking
import zio.k8s.codegen.codegen.{ClassifiedResource, ClientModuleGenerator, IdentifiedAction, IdentifiedPath, IdentifiedSchema, ModelGenerator, SupportedResource}
import zio.nio.core.file.Path

import java.io.File
import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

object K8sResourceCodegen extends ModelGenerator with ClientModuleGenerator {

  def generateAll(targetDir: Path, log: Logger): ZIO[Blocking, Throwable, Seq[File]] =
    for {
      // Loading
      spec <- loadK8sSwagger(log)
      scalafmt <- ZIO.effect(Scalafmt.create(this.getClass.getClassLoader))

      // Identifying
      definitions = spec.getComponents.getSchemas.asScala.flatMap((IdentifiedSchema.identifyDefinition _).tupled).toSet
      definitionMap = definitions.map(d => d.name -> d).toMap

      paths = spec.getPaths.asScala.flatMap((IdentifiedPath.identifyPath _).tupled)
      identified = paths.collect { case i: IdentifiedAction => i }

      // Classifying
      resources = ClassifiedResource.classifyActions(definitionMap, identified)

      // Generating code
      packagePaths <- generateAllPackages(scalafmt, log, targetDir, definitionMap, resources)
      modelPaths <- generateAllModels(scalafmt, log, targetDir, definitions)
    } yield (packagePaths union modelPaths).map(_.toFile).toSeq

  private def loadK8sSwagger(log: Logger): Task[OpenAPI] =
    Task.effect {
      log.info("Loading k8s-swagger.json")
      val stream = classOf[ClientModuleGenerator].getResourceAsStream("/k8s-swagger.json")
      val rawJson = new String(stream.readAllBytes(), StandardCharsets.UTF_8)

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
