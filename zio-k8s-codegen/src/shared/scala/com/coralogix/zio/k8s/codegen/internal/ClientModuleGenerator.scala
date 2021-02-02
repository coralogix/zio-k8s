package com.coralogix.zio.k8s.codegen.internal

import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.blocking.Blocking
import zio.{ Task, ZIO }
import com.coralogix.zio.k8s.codegen.internal.Conversions.{ groupNameToPackageName, splitName }
import com.coralogix.zio.k8s.codegen.internal.CodegenIO._
import zio.nio.core.file.Path
import zio.nio.file.Files

import scala.meta._

trait ClientModuleGenerator {
  def generateModuleCode(
    basePackageName: String,
    modelPackageName: String,
    name: String,
    entity: String,
    statusEntity: Option[String],
    gvk: GroupVersionKind,
    isNamespaced: Boolean,
    subresources: Set[SubresourceId],
    crdYaml: Option[Path]
  ): Task[String] =
    ZIO.effect {
      val basePackage =
        if (gvk.group.nonEmpty)
          s"$basePackageName.${groupNameToPackageName(gvk.group).mkString(".")}"
            .parse[Term]
            .get
            .asInstanceOf[Term.Ref]
        else
          basePackageName.parse[Term].get.asInstanceOf[Term.Ref]
      val moduleName = Term.Name(name)
      val entityName = Term.Name(entity)
      val entityT = Type.Name(entityName.value)

      val ver = Term.Name(gvk.version)

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
              val customResourceDefinition: ZIO[Blocking, Throwable, com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition] =
                for {
                  rawYaml <- ZStream.fromInputStream(getClass.getResourceAsStream($yamlPathLit))
                    .transduce(ZTransducer.utf8Decode)
                    .fold("")(_ ++ _).orDie
                  crd <- ZIO.fromEither(_root_.io.circe.yaml.parser.parse(rawYaml).flatMap(_.as[com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition]))
                } yield crd
             """)
          case None           =>
            List.empty
        }

      val code =
        if (isNamespaced) {
          val statusDefinitions =
            if (statusEntity.isDefined)
              List(
                q"""
               def replaceStatus(
                of: $entityT,
                updatedStatus: $statusT,
                namespace: K8sNamespace,
                dryRun: Boolean = false
              ): ZIO[Has[NamespacedResourceStatus[$statusT, $entityT]], K8sFailure, $entityT] = {
                  ResourceClient.namespaced.replaceStatus(of, updatedStatus, namespace, dryRun)
                }
             """,
                q"""
                  def getStatus(
                  name: String,
                  namespace: K8sNamespace
                ): ZIO[Has[
                  NamespacedResourceStatus[$statusT, $entityT]
                ], K8sFailure, $entityT] =
                  ResourceClient.namespaced.getStatus(name, namespace)
                 """
              )
            else
              List.empty

          val subresourceDefinitions =
            subresources.toList
              .sortBy(_.name)
              .flatMap { subresource =>
                val capName = subresource.name.capitalize
                val getTerm = Term.Name(s"get$capName")
                val putTerm = Term.Name(s"replace$capName")
                val postTerm = Term.Name(s"create$capName")

                val modelT: Type.Ref = getSubresourceModelType(subresource)
                val clientT = getNamespacedSubresourceWrapperType(subresource, entityT)

                subresource.actionVerbs.flatMap {
                  case "get"  =>
                    List(
                      q"""
                        def $getTerm(
                          name: String,
                          namespace: K8sNamespace
                        ): ZIO[Has[$clientT], K8sFailure, $modelT] =
                          ZIO.accessM[Has[$clientT]](_.get.$getTerm(name, namespace))
                        """
                    )
                  case "put"  =>
                    List(
                      q"""
                          def $putTerm(
                            name: String,
                            updatedValue: $modelT,
                            namespace: K8sNamespace,
                            dryRun: Boolean = false
                          ): ZIO[Has[$clientT], K8sFailure, $modelT] =
                            ZIO.accessM[Has[$clientT]](_.get.$putTerm(name, updatedValue, namespace, dryRun))
                        """
                    )
                  case "post" =>
                    List(
                      q"""
                          def $postTerm(
                            value: $modelT,
                            namespace: K8sNamespace,
                            dryRun: Boolean = false
                          ): ZIO[Has[$clientT], K8sFailure, $modelT] =
                            ZIO.accessM[Has[$clientT]](_.get.$postTerm(value, namespace, dryRun))
                        """
                    )
                  case _      => List.empty
                }
              }

          val baseConstructor =
            if (statusEntity.isDefined)
              q"""ResourceClient.namespaced.liveWithStatus[$statusT, $entityT](implicitly[ResourceMetadata[$entityT]].resourceType)"""
            else
              q"""ResourceClient.namespaced.liveWithoutStatus[$entityT](implicitly[ResourceMetadata[$entityT]].resourceType)"""

          val constructor =
            subresources.toList.foldLeft[Term](baseConstructor) { case (expr, subresource) =>
              val clientTerm = getNamespacedSubresourceWrapperTerm(subresource)
              q"""$expr ++ $clientTerm.live[$entityT]"""
            }

          val live =
            q"""def live: ZLayer[SttpClient with Has[K8sCluster], Nothing, $typeAliasT] = $constructor"""
          val hasTypes =
            ((if (statusEntity.isDefined)
                List[Type](t"Has[NamespacedResourceStatus[$statusT, $entityT]]")
              else Nil) ::
              subresources.toList.map { subresource =>
                val clientT = getNamespacedSubresourceWrapperType(subresource, entityT)
                List[Type](t"Has[$clientT]")
              }).flatten
          val typeAliasRhs: Type = hasTypes.foldLeft[Type](t"Has[NamespacedResource[$entityT]]") {
            case (l, r) =>
              Type.With(l, r)
          }
          val typeAlias = q"""type ${typeAliasT} = $typeAliasRhs"""

          q"""package $basePackage.$moduleName {

          $entityImport
          import com.coralogix.zio.k8s.model.pkg.apis.meta.v1._
          import com.coralogix.zio.k8s.model._
          import com.coralogix.zio.k8s.client.{NamespacedResource, NamespacedResourceStatus, K8sFailure, ResourceClient}
          import com.coralogix.zio.k8s.client.model.{
            K8sCluster,
            K8sNamespace,
            K8sResourceType,
            ResourceMetadata,
            TypedWatchEvent
          }
          import sttp.client3.httpclient.zio.SttpClient
          import zio.blocking.Blocking
          import zio.clock.Clock
          import zio.stream.{ZStream, ZTransducer}
          import zio.{ Has, Task, ZIO, ZLayer }

          package object $ver {
            $typeAlias

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

            ..$subresourceDefinitions

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
               """,
                q"""
                  def getStatus(name: String): ZIO[Has[ClusterResourceStatus[$statusT, $entityT]], K8sFailure, $entityT] =
                  ResourceClient.cluster.getStatus(name)
                 """
              )
            else
              List.empty

          val subresourceDefinitions =
            subresources.toList
              .sortBy(_.name)
              .flatMap { subresource =>
                val capName = subresource.name.capitalize
                val getTerm = Term.Name(s"get$capName")
                val putTerm = Term.Name(s"replace$capName")
                val postTerm = Term.Name(s"create$capName")

                val modelT: Type.Ref = getSubresourceModelType(subresource)
                val clientT = getClusterSubresourceWrapperType(subresource, entityT)

                subresource.actionVerbs.flatMap {
                  case "get"  =>
                    List(
                      q"""
                        def $getTerm(
                          name: String,
                        ): ZIO[Has[$clientT], K8sFailure, $modelT] =
                          ZIO.accessM[Has[$clientT]](_.get.$getTerm(name))
                        """
                    )
                  case "put"  =>
                    List(
                      q"""
                          def $putTerm(
                            name: String,
                            updatedValue: $modelT,
                            dryRun: Boolean = false
                          ): ZIO[Has[$clientT], K8sFailure, $modelT] =
                            ZIO.accessM[Has[$clientT]](_.get.$putTerm(name, updatedValue, dryRun))
                        """
                    )
                  case "post" =>
                    List(
                      q"""
                          def $postTerm(
                            value: $modelT,
                            dryRun: Boolean = false
                          ): ZIO[Has[$clientT], K8sFailure, $modelT] =
                            ZIO.accessM[Has[$clientT]](_.get.$postTerm(value, dryRun))
                        """
                    )
                  case _      => List.empty
                }
              }

          val baseConstructor =
            if (statusEntity.isDefined)
              q"""ResourceClient.cluster.liveWithStatus[$statusT, $entityT](implicitly[ResourceMetadata[$entityT]].resourceType)"""
            else
              q"""ResourceClient.cluster.liveWithoutStatus[$entityT](implicitly[ResourceMetadata[$entityT]].resourceType)"""

          val constructor =
            subresources.toList.foldLeft[Term](baseConstructor) { case (expr, subresource) =>
              val clientTerm = getClusterSubresourceWrapperTerm(subresource)
              q"""$expr ++ $clientTerm.live[$entityT]"""
            }

          val live =
            q"""def live: ZLayer[SttpClient with Has[K8sCluster], Nothing, $typeAliasT] = $constructor"""

          val hasTypes =
            ((if (statusEntity.isDefined)
                List[Type](t"Has[ClusterResourceStatus[$statusT, $entityT]]")
              else Nil) ::
              subresources.toList.map { subresource =>
                val clientT = getClusterSubresourceWrapperType(subresource, entityT)
                List[Type](t"Has[$clientT]")
              }).flatten
          val typeAliasRhs: Type = hasTypes.foldLeft[Type](t"Has[ClusterResource[$entityT]]") {
            case (l, r) =>
              Type.With(l, r)
          }
          val typeAlias = q"""type ${typeAliasT} = $typeAliasRhs"""

          q"""package $basePackage.$moduleName {

          $entityImport
          import com.coralogix.zio.k8s.model.pkg.apis.meta.v1._
          import com.coralogix.zio.k8s.model._
          import com.coralogix.zio.k8s.client.{ClusterResource, ClusterResourceStatus, K8sFailure, ResourceClient}
          import com.coralogix.zio.k8s.client.model.{
            K8sCluster,
            K8sNamespace,
            K8sResourceType,
            ResourceMetadata,
            TypedWatchEvent
          }
          import sttp.client3.httpclient.zio.SttpClient
          import zio.blocking.Blocking
          import zio.clock.Clock
          import zio.stream.{ZStream, ZTransducer}
          import zio.{ Has, Task, ZIO, ZLayer }

          package object $ver {
            $typeAlias

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

            ..$subresourceDefinitions

            ..$customResourceDefinition

            $live
          }
          }
          """
        }

      code.toString()
    }

  private def getSubresourceModelType(subresource: SubresourceId): Type.Ref = {
    val (modelPkg, modelName) = splitName(subresource.modelName)
    if (modelPkg.nonEmpty) {
      val modelNs = modelPkg.mkString(".").parse[Term].get.asInstanceOf[Term.Ref]
      Type.Select(modelNs, Type.Name(modelName))
    } else {
      Type.Name(modelName)
    }
  }

  private def getSubresourceWrapperType(
    subresource: SubresourceId,
    wrapperName: Type.Name,
    entityT: Type
  ): Type = {
    val (modelPkg, _) = splitName(subresource.modelName)
    val ns =
      if (modelPkg.nonEmpty) {
        "com.coralogix.zio.k8s.client.subresources." + modelPkg.mkString(".")
      } else {
        "com.coralogix.zio.k8s.client.subresources"
      }
    val modelNs = ns.parse[Term].get.asInstanceOf[Term.Ref]
    val cons = Type.Select(modelNs, wrapperName)
    t"$cons[$entityT]"
  }

  private def getNamespacedSubresourceWrapperType(
    subresource: SubresourceId,
    entityT: Type
  ): Type = {
    val capName = subresource.name.capitalize
    val wrapperName = Type.Name(s"Namespaced${capName}Subresource")
    getSubresourceWrapperType(subresource, wrapperName, entityT)
  }

  private def getClusterSubresourceWrapperType(subresource: SubresourceId, entityT: Type): Type = {
    val capName = subresource.name.capitalize
    val wrapperName = Type.Name(s"Cluster${capName}Subresource")
    getSubresourceWrapperType(subresource, wrapperName, entityT)
  }

  private def getSubresourceWrapperTerm(
    subresource: SubresourceId,
    wrapperName: Term.Name
  ): Term = {
    val (modelPkg, _) = splitName(subresource.modelName)
    val ns =
      if (modelPkg.nonEmpty) {
        "com.coralogix.zio.k8s.client.subresources." + modelPkg.mkString(".")
      } else {
        "com.coralogix.zio.k8s.client.subresources"
      }
    val modelNs = ns.parse[Term].get.asInstanceOf[Term.Ref]
    Term.Select(modelNs, wrapperName)
  }

  private def getNamespacedSubresourceWrapperTerm(subresource: SubresourceId): Term = {
    val capName = subresource.name.capitalize
    val wrapperName = Term.Name(s"Namespaced${capName}Subresource")
    getSubresourceWrapperTerm(subresource, wrapperName)
  }

  private def getClusterSubresourceWrapperTerm(subresource: SubresourceId): Term = {
    val capName = subresource.name.capitalize
    val wrapperName = Term.Name(s"Cluster${capName}Subresource")
    getSubresourceWrapperTerm(subresource, wrapperName)
  }
}
