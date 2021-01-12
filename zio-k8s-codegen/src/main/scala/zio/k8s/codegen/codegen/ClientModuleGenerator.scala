package zio.k8s.codegen.codegen

import com.twilio.guardrail.generators.syntax._
import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.blocking.Blocking
import zio.{ Task, ZIO }
import zio.k8s.codegen.codegen.Conversions.{ groupNameToPackageName, splitName }
import zio.k8s.codegen.CodegenIO._
import zio.k8s.codegen.k8smodel.CustomResourceDefinition
import zio.nio.core.file.Path
import zio.nio.file.Files

import scala.meta._

trait ClientModuleGenerator {
  private val clientRoot = Vector("zio", "k8s", "client")

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
               modelPackageName = "zio.k8s.model." + entityPkg.mkString("."),
               name = resource.plural,
               entity = entity,
               statusEntity =
                 findStatusEntity(definitionMap, resource.modelName).map(s => s"zio.k8s.model.$s"),
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

  def generateCustomResourceModuleCode(
    crd: CustomResourceDefinition,
    version: String,
    yamlPath: Path
  ): Task[String] = {
    val entityName = crd.spec.names.singular.getOrElse(crd.spec.names.plural)
    val moduleName = crd.spec.names.plural
    generateModuleCode(
      "zio.k8s.client",
      if (crd.spec.group.nonEmpty) {
        val groupPart = groupNameToPackageName(crd.spec.group).mkString(".")
        s"zio.k8s.client.$groupPart.definitions.$entityName.$version"
      } else
        s"zio.k8s.client.definitions.$entityName.$version",
      moduleName,
      entityName.toPascalCase,
      crd.spec.versions
        .find(_.name == version)
        .flatMap(_.subresources.flatMap(_.status))
        .map(_ => entityName.toPascalCase + ".Status"),
      crd.spec.group,
      crd.spec.names.kind,
      version,
      crd.spec.scope == "Namespaced",
      Some(yamlPath)
    )
  }

  def generateModuleCode(
    basePackageName: String,
    modelPackageName: String,
    name: String,
    entity: String,
    statusEntity: Option[String],
    group: String,
    kind: String,
    version: String,
    isNamespaced: Boolean,
    crdYaml: Option[Path]
  ): Task[String] =
    ZIO.effect {
      val basePackage =
        if (group.nonEmpty)
          s"$basePackageName.${groupNameToPackageName(group).mkString(".")}"
            .parse[Term]
            .get
            .asInstanceOf[Term.Ref]
        else
          basePackageName.parse[Term].get.asInstanceOf[Term.Ref]
      val moduleName = Term.Name(name)
      val entityName = Term.Name(entity)
      val entityT = Type.Name(entityName.value)

      val ver = Term.Name(version)

      val pluaralLit = Lit.String(name)
      val groupLit = Lit.String(group)
      val versionLit = Lit.String(version)

      val kindLit = Lit.String(kind)
      val apiVersionLit = Lit.String(s"${group}/${version}")

      val dtoPackage = modelPackageName.parse[Term].get.asInstanceOf[Term.Ref]
      val entityImport =
        Import(List(Importer(dtoPackage, List(Importee.Name(Name(entityName.value))))))

      val statusT = statusEntity.map(s => s.parse[Type].get).getOrElse(t"Nothing")
      val typeAliasT = Type.Name(entity + "s")

      val customResourceDefinition =
        crdYaml match {
          case Some(yamlPath) =>
            val yamlPathLit = Lit.String("/" + yamlPath.toString)

            List(q"""
              val customResourceDefinition: ZIO[Blocking, Throwable, zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition] =
                for {
                  rawYaml <- ZStream.fromInputStream(getClass.getResourceAsStream($yamlPathLit))
                    .transduce(ZTransducer.utf8Decode)
                    .fold("")(_ ++ _).orDie
                  crd <- ZIO.fromEither(io.circe.yaml.parser.parse(rawYaml).flatMap(_.as[zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition]))
                } yield crd
             """)
          case None =>
            List.empty
        }

      val code =
        if (isNamespaced) {
          val statusDefinitions =
            if (statusEntity.isDefined)
              List(q"""
               def replaceStatus(
                of: $entityT,
                updatedStatus: $statusT,
                namespace: K8sNamespace,
                dryRun: Boolean = false
              ): ZIO[Has[NamespacedResourceStatus[$statusT, $entityT]], K8sFailure, $entityT] = {
                  ResourceClient.namespaced.replaceStatus(of, updatedStatus, namespace, dryRun)
                }
             """)
            else
              List.empty

          val live =
            if (statusEntity.isDefined)
              q"""
              def live: ZLayer[SttpClient with ZConfig[K8sCluster], Nothing, Has[NamespacedResource[$entityT]] with Has[NamespacedResourceStatus[$statusT, $entityT]]] =
                ResourceClient.namespaced.liveWithStatus[$statusT, $entityT](metadata.resourceType)
             """
            else
              q"""
              def live: ZLayer[SttpClient with ZConfig[K8sCluster], Nothing, Has[NamespacedResource[$entityT]]] =
                ResourceClient.namespaced.liveWithoutStatus[$entityT](metadata.resourceType)
             """

          val typeAlias =
            if (statusEntity.isDefined)
              q"""type ${typeAliasT} = Has[NamespacedResource[$entityT]] with Has[NamespacedResourceStatus[$statusT, $entityT]]"""
            else
              q"""type ${typeAliasT} = Has[NamespacedResource[$entityT]]"""

          q"""package $basePackage.$moduleName {

          $entityImport
          import zio.k8s.model.pkg.apis.meta.v1._
          import zio.k8s.client.{NamespacedResource, NamespacedResourceStatus, K8sFailure, ResourceClient}
          import zio.k8s.client.model.{
            K8sCluster,
            K8sNamespace,
            K8sResourceType,
            ResourceMetadata,
            TypedWatchEvent
          }
          import sttp.client3.httpclient.zio.SttpClient
          import zio.blocking.Blocking
          import zio.clock.Clock
          import zio.config.ZConfig
          import zio.stream.{ZStream, ZTransducer}
          import zio.{ Has, Task, ZIO, ZLayer }

          package object $ver {
            $typeAlias

            implicit val metadata: ResourceMetadata[$entityT] =
              new ResourceMetadata[$entityT] {
                override val kind: String = $kindLit
                override val apiVersion: String = $apiVersionLit
                override val resourceType: K8sResourceType = K8sResourceType($pluaralLit, $groupLit, $versionLit)
              }

            def getAll(
              namespace: Option[K8sNamespace],
              chunkSize: Int = 10
            ): ZStream[Has[NamespacedResource[$entityT]], K8sFailure, $entityT] =
              ResourceClient.namespaced.getAll(namespace, chunkSize)

            def watch(
              namespace: Option[K8sNamespace],
              resourceVersion: Option[String]
            ): ZStream[Has[NamespacedResource[$entityT]], K8sFailure, TypedWatchEvent[$entityT]] =
              ResourceClient.namespaced.watch(namespace, resourceVersion)

            def watchForever(
              namespace: Option[K8sNamespace]
            ): ZStream[Has[NamespacedResource[$entityT]] with Clock, K8sFailure, TypedWatchEvent[$entityT]] =
              ResourceClient.namespaced.watchForever(namespace)

            def get(
              name: String,
              namespace: K8sNamespace
            ): ZIO[Has[NamespacedResource[$entityT]], K8sFailure, $entityT] =
              ResourceClient.namespaced.get(name, namespace)

            def create(
              newResource: $entityT,
              namespace: K8sNamespace,
              dryRun: Boolean = false
            ): ZIO[Has[NamespacedResource[$entityT]], K8sFailure, $entityT] =
              ResourceClient.namespaced.create(newResource, namespace, dryRun)

            def replace(
              name: String,
              updatedResource: $entityT,
              namespace: K8sNamespace,
              dryRun: Boolean = false
            ): ZIO[Has[NamespacedResource[$entityT]], K8sFailure, $entityT] =
              ResourceClient.namespaced.replace(name, updatedResource, namespace, dryRun)

            def delete(
              name: String,
              deleteOptions: DeleteOptions,
              namespace: K8sNamespace,
              dryRun: Boolean = false
            ): ZIO[Has[NamespacedResource[$entityT]], K8sFailure, Status] =
              ResourceClient.namespaced.delete(name, deleteOptions, namespace, dryRun)

            ..$statusDefinitions

            ..$customResourceDefinition

            $live
          }
          }
          """
        } else {
          val statusDefinitions =
            if (statusEntity.isDefined)
              List(
                q"""
               def replaceStatus(
                of: $entityT,
                updatedStatus: $statusT,
                dryRun: Boolean = false
              ): ZIO[Has[ClusterResourceStatus[$statusT, $entityT]], K8sFailure, $entityT] = {
                ResourceClient.cluster.replaceStatus(of, updatedStatus, dryRun)
                }
               """
              )
            else
              List.empty

          val live =
            if (statusEntity.isDefined)
              q"""
              def live: ZLayer[SttpClient with ZConfig[K8sCluster], Nothing, Has[ClusterResource[$entityT]] with Has[ClusterResourceStatus[$statusT, $entityT]]] =
                ResourceClient.cluster.liveWithStatus[$statusT, $entityT](metadata.resourceType)
             """
            else
              q"""
              def live: ZLayer[SttpClient with ZConfig[K8sCluster], Nothing, Has[ClusterResource[$entityT]]] =
                ResourceClient.cluster.liveWithoutStatus[$entityT](metadata.resourceType)
             """

          val typeAlias =
            if (statusEntity.isDefined)
              q"""type ${typeAliasT} = Has[ClusterResource[$entityT]] with Has[ClusterResourceStatus[$statusT, $entityT]]"""
            else
              q"""type ${typeAliasT} = Has[ClusterResource[$entityT]]"""

          q"""package $basePackage.$moduleName {

          $entityImport
          import zio.k8s.model.pkg.apis.meta.v1._
          import zio.k8s.client.{ClusterResource, ClusterResourceStatus, K8sFailure, ResourceClient}
          import zio.k8s.client.model.{
            K8sCluster,
            K8sNamespace,
            K8sResourceType,
            ResourceMetadata,
            TypedWatchEvent
          }
          import sttp.client3.httpclient.zio.SttpClient
          import zio.blocking.Blocking
          import zio.clock.Clock
          import zio.config.ZConfig
          import zio.stream.{ZStream, ZTransducer}
          import zio.{ Has, Task, ZIO, ZLayer }

          package object $ver {
            $typeAlias

            implicit val metadata: ResourceMetadata[$entityT] =
              new ResourceMetadata[$entityT] {
                override val kind: String = $kindLit
                override val apiVersion: String = $apiVersionLit
                override val resourceType: K8sResourceType = K8sResourceType($pluaralLit, $groupLit, $versionLit)
              }

            def getAll(
              chunkSize: Int = 10
            ): ZStream[Has[ClusterResource[$entityT]], K8sFailure, $entityT] =
              ResourceClient.cluster.getAll(chunkSize)

            def watch(
              resourceVersion: Option[String]
            ): ZStream[Has[ClusterResource[$entityT]], K8sFailure, TypedWatchEvent[$entityT]] =
              ResourceClient.cluster.watch(resourceVersion)

            def watchForever(
            ): ZStream[Has[ClusterResource[$entityT]] with Clock, K8sFailure, TypedWatchEvent[$entityT]] =
              ResourceClient.cluster.watchForever()

            def get(name: String): ZIO[Has[ClusterResource[$entityT]], K8sFailure, $entityT] =
              ResourceClient.cluster.get(name)

            def create(
              newResource: $entityT,
              dryRun: Boolean = false
            ): ZIO[Has[ClusterResource[$entityT]], K8sFailure, $entityT] =
              ResourceClient.cluster.create(newResource, dryRun)

            def replace(
              name: String,
              updatedResource: $entityT,
              dryRun: Boolean = false
            ): ZIO[Has[ClusterResource[$entityT]], K8sFailure, $entityT] =
              ResourceClient.cluster.replace(name, updatedResource, dryRun)

            def delete(
              name: String,
              deleteOptions: DeleteOptions,
              dryRun: Boolean = false
            ): ZIO[Has[ClusterResource[$entityT]], K8sFailure, Status] =
              ResourceClient.cluster.delete(name, deleteOptions, dryRun)

            ..$statusDefinitions

            ..$customResourceDefinition

            $live
          }
          }
          """
        }

      code.toString()
    }
}
