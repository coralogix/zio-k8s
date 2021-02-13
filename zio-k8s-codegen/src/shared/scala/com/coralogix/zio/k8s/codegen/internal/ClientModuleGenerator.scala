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

      val entityT =
        if (entity == "Service")
          Type.Name("ServiceModel")
        else
          Type.Name(entity)

      val ver = Term.Name(gvk.version)

      val dtoPackage = modelPackageName.parse[Term].get.asInstanceOf[Term.Ref]
      val entityImport =
        if (entity == "Service")
          Import(
            List(Importer(dtoPackage, List(Importee.Rename(Name("Service"), Name("ServiceModel")))))
          )
        else
          Import(List(Importer(dtoPackage, List(Importee.Name(Name(entityName.value))))))

      val statusT = statusEntity.map(s => s.parse[Type].get).getOrElse(t"Nothing")
      val typeAliasT = Type.Name(entity + "s")
      val typeAliasTerm = Term.Name(entity + "s")
      val typeAliasGenericT = Type.Select(typeAliasTerm, Type.Name("Generic"))

      val customResourceDefinition: List[Defn] =
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

      val clientList =
        param"client: ResourceClient[$entityT]" ::
          (((if (statusEntity.isDefined)
               List[Term.Param](
                 param"statusClient: ResourceStatusClient[$statusT, $entityT]"
               )
             else Nil) ::
            subresources.toList.map { subresource =>
              val clientName = Term.Name(subresource.name + "Client")
              val modelT = getSubresourceModelType(modelPackageName, subresource)
              List(param"$clientName: SubresourceClient[$modelT]")
            }).flatten)

      val clientExposure =
        q"override val asGenericResource: Resource[$entityT] = client" ::
          (((if (statusEntity.isDefined)
               List(
                 q"override val asGenericResourceStatus: ResourceStatus[$statusT, $entityT] = statusClient"
               )
             else Nil) ::
            subresources.toList.map { subresource =>
              val capName = subresource.name.capitalize
              val clientName = Term.Name(subresource.name + "Client")
              val asGenericTerm = Pat.Var(Term.Name(s"asGeneric${capName}Subresource"))
              val modelT = getSubresourceModelType(modelPackageName, subresource)
              List(q"override val $asGenericTerm: SubresourceClient[$modelT] = $clientName")
            }).flatten)

      val clientConstruction: List[Term] =
        getClientConstruction(modelPackageName, statusEntity, subresources, entityT, statusT)

      val live =
        q"""val live: ZLayer[SttpClient with Has[K8sCluster], Nothing, $typeAliasT] =
                  ZLayer.fromServices[SttpClient.Service, K8sCluster, Service] {
                    (backend: SttpClient.Service, cluster: K8sCluster) => {
                      val resourceType = implicitly[ResourceMetadata[$entityT]].resourceType
                      new Live(..$clientConstruction)
                    }
                  }
             """

      val any =
        q"""val any: ZLayer[$typeAliasT, Nothing, $typeAliasT] = ZLayer.requires[$typeAliasT]"""

//      val test =
//        q"""val test: ZLayer[$typeAliasT with $typeAliasTestT"""

      val code =
        if (isNamespaced) {
          // NAMESPACED RESOURCE

          val statusImpls =
            if (statusEntity.isDefined)
              List(
                q"""
               override def replaceStatus(
                of: $entityT,
                updatedStatus: $statusT,
                namespace: K8sNamespace,
                dryRun: Boolean = false
              ): ZIO[Any, K8sFailure, $entityT] = {
                  statusClient.replaceStatus(of, updatedStatus, Some(namespace), dryRun)
                }
             """,
                q"""
                  override def getStatus(
                  name: String,
                  namespace: K8sNamespace
                ): ZIO[Any, K8sFailure, $entityT] =
                  statusClient.getStatus(name, Some(namespace))
                 """
              )
            else
              List.empty

          val statusAccessors: List[Defn] =
            if (statusEntity.isDefined)
              List(
                q"""
               def replaceStatus(
                of: $entityT,
                updatedStatus: $statusT,
                namespace: K8sNamespace,
                dryRun: Boolean = false
              ): ZIO[$typeAliasT, K8sFailure, $entityT] = {
                  ZIO.accessM(_.get.replaceStatus(of, updatedStatus, namespace, dryRun))
                }
             """,
                q"""
                  def getStatus(
                  name: String,
                  namespace: K8sNamespace
                ): ZIO[$typeAliasT, K8sFailure, $entityT] =
                  ZIO.accessM(_.get.getStatus(name, namespace))
                 """
              )
            else
              List.empty

          val subresourceImpls =
            subresources.toList
              .sortBy(_.name)
              .flatMap { subresource =>
                val capName = subresource.name.capitalize
                val getTerm = Term.Name(s"get$capName")
                val putTerm = Term.Name(s"replace$capName")
                val postTerm = Term.Name(s"create$capName")

                val clientName = Term.Name(subresource.name + "Client")
                val modelT: Type.Ref = getSubresourceModelType(modelPackageName, subresource)

                subresource.actionVerbs.flatMap {
                  case "get"  =>
                    List(
                      q"""
                        override def $getTerm(
                          name: String,
                          namespace: K8sNamespace
                        ): ZIO[Any, K8sFailure, $modelT] =
                          $clientName.get(name, Some(namespace))
                        """
                    )
                  case "put"  =>
                    List(
                      q"""
                          override def $putTerm(
                            name: String,
                            updatedValue: $modelT,
                            namespace: K8sNamespace,
                            dryRun: Boolean = false
                          ): ZIO[Any, K8sFailure, $modelT] =
                            $clientName.replace(name, updatedValue, Some(namespace), dryRun)
                        """
                    )
                  case "post" =>
                    List(
                      q"""
                          override def $postTerm(
                            value: $modelT,
                            namespace: K8sNamespace,
                            dryRun: Boolean = false
                          ): ZIO[Any, K8sFailure, $modelT] =
                            $clientName.create(value, Some(namespace), dryRun)
                        """
                    )
                  case _      => List.empty
                }
              }

          val subresourceAccessors: List[Defn] =
            subresources.toList
              .sortBy(_.name)
              .flatMap { subresource =>
                val capName = subresource.name.capitalize
                val getTerm = Term.Name(s"get$capName")
                val putTerm = Term.Name(s"replace$capName")
                val postTerm = Term.Name(s"create$capName")

                val modelT: Type.Ref = getSubresourceModelType(modelPackageName, subresource)

                subresource.actionVerbs.flatMap {
                  case "get"  =>
                    List(
                      q"""
                        def $getTerm(
                          name: String,
                          namespace: K8sNamespace
                        ): ZIO[$typeAliasT, K8sFailure, $modelT] =
                          ZIO.accessM(_.get.$getTerm(name, namespace))
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
                          ): ZIO[$typeAliasT, K8sFailure, $modelT] =
                            ZIO.accessM(_.get.$putTerm(name, updatedValue, namespace, dryRun))
                        """
                    )
                  case "post" =>
                    List(
                      q"""
                          def $postTerm(
                            value: $modelT,
                            namespace: K8sNamespace,
                            dryRun: Boolean = false
                          ): ZIO[$typeAliasT, K8sFailure, $modelT] =
                            ZIO.accessM(_.get.$postTerm(value, namespace, dryRun))
                        """
                    )
                  case _      => List.empty
                }
              }

          val mainInterface = t"NamespacedResource[$entityT]"
          val extraInterfaces =
            ((if (statusEntity.isDefined)
                List[Type](t"NamespacedResourceStatus[$statusT, $entityT]")
              else Nil) ::
              subresources.toList.map { subresource =>
                List(getNamespacedSubresourceWrapperType(subresource, entityT))
              }).flatten

          val typeAliasRhs: Type = extraInterfaces.foldLeft[Type](t"Has[$mainInterface]") {
            case (l, r) =>
              Type.With(l, t"Has[$r]")
          }
          val serviceT = Type.Select(typeAliasTerm, Type.Name("Service"))
          val typeAlias = q"""type ${typeAliasT} = ${t"Has[$serviceT]"}"""
          val typeAliasGeneric = q"""type Generic = $typeAliasRhs"""

          val mainInterfaceI = Init(mainInterface, Name.Anonymous(), List.empty)
          val extraInterfaceIs = extraInterfaces.map(t => Init(t, Name.Anonymous(), List.empty))

          val interfacesWrappedInHas =
            extraInterfaces.foldLeft[Term](q"Has[$mainInterface](this)") { case (l, t) =>
              q"$l ++ Has[$t](this)"
            }

          q"""package $basePackage.$ver {

          $entityImport
          import com.coralogix.zio.k8s.model.pkg.apis.meta.v1._
          import com.coralogix.zio.k8s.model._
          import com.coralogix.zio.k8s.client.{Resource, ResourceStatus, NamespacedResource, NamespacedResourceStatus, K8sFailure}
          import com.coralogix.zio.k8s.client.impl.{ResourceClient, ResourceStatusClient, SubresourceClient}
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

          package object $moduleName {
            $typeAlias

            object $typeAliasTerm {
              $typeAliasGeneric

              trait Service
                extends $mainInterfaceI with ..${extraInterfaceIs} {

                val asGeneric: $typeAliasGenericT = $interfacesWrappedInHas
              }

              final class Live(..$clientList) extends Service {
                override def getAll(
                  namespace: Option[K8sNamespace],
                  chunkSize: Int = 10
                ): ZStream[Any, K8sFailure, $entityT] =
                   client.getAll(namespace, chunkSize)

                override def watch(
                  namespace: Option[K8sNamespace],
                  resourceVersion: Option[String]
                ): ZStream[Any, K8sFailure, TypedWatchEvent[$entityT]] =
                  client.watch(namespace, resourceVersion)

                override def watchForever(
                  namespace: Option[K8sNamespace]
                ): ZStream[Clock, K8sFailure, TypedWatchEvent[$entityT]] =
                  client.watchForever(namespace)

                override def get(
                  name: String,
                  namespace: K8sNamespace
                ): ZIO[Any, K8sFailure, $entityT] =
                  client.get(name, Some(namespace))

                override def create(
                  newResource: $entityT,
                  namespace: K8sNamespace,
                  dryRun: Boolean = false
                ): ZIO[Any, K8sFailure, $entityT] =
                  client.create(newResource, Some(namespace), dryRun)

                override def replace(
                  name: String,
                  updatedResource: $entityT,
                  namespace: K8sNamespace,
                  dryRun: Boolean = false
                ): ZIO[Any, K8sFailure, $entityT] =
                 client.replace(name, updatedResource, Some(namespace), dryRun)

                override def delete(
                  name: String,
                  deleteOptions: DeleteOptions,
                  namespace: K8sNamespace,
                  dryRun: Boolean = false
                ): ZIO[Any, K8sFailure, Status] =
                  client.delete(name, deleteOptions, Some(namespace), dryRun)

                ..$statusImpls

                ..$subresourceImpls

                ..$clientExposure
              }

              $live
              $any
            }

            def getAll(
              namespace: Option[K8sNamespace],
              chunkSize: Int = 10
            ): ZStream[$typeAliasT, K8sFailure, $entityT] =
              ZStream.accessStream(_.get.getAll(namespace, chunkSize))

            def watch(
              namespace: Option[K8sNamespace],
              resourceVersion: Option[String]
            ): ZStream[$typeAliasT, K8sFailure, TypedWatchEvent[$entityT]] =
              ZStream.accessStream(_.get.watch(namespace, resourceVersion))

            def watchForever(
              namespace: Option[K8sNamespace]
            ): ZStream[$typeAliasT with Clock, K8sFailure, TypedWatchEvent[$entityT]] =
              ZStream.accessStream(_.get.watchForever(namespace))

            def get(
              name: String,
              namespace: K8sNamespace
            ): ZIO[$typeAliasT, K8sFailure, $entityT] =
              ZIO.accessM(_.get.get(name, namespace))

            def create(
              newResource: $entityT,
              namespace: K8sNamespace,
              dryRun: Boolean = false
            ): ZIO[$typeAliasT, K8sFailure, $entityT] =
              ZIO.accessM(_.get.create(newResource, namespace, dryRun))

            def replace(
              name: String,
              updatedResource: $entityT,
              namespace: K8sNamespace,
              dryRun: Boolean = false
            ): ZIO[$typeAliasT, K8sFailure, $entityT] =
              ZIO.accessM(_.get.replace(name, updatedResource, namespace, dryRun))

            def delete(
              name: String,
              deleteOptions: DeleteOptions,
              namespace: K8sNamespace,
              dryRun: Boolean = false
            ): ZIO[$typeAliasT, K8sFailure, Status] =
              ZIO.accessM(_.get.delete(name, deleteOptions, namespace, dryRun))

            ..$statusAccessors

            ..$subresourceAccessors

            ..$customResourceDefinition

          }
          }
          """
        } else {
          // CLUSTER RESOURCE

          val statusImpls =
            if (statusEntity.isDefined)
              List(
                q"""
               override def replaceStatus(
                of: $entityT,
                updatedStatus: $statusT,
                dryRun: Boolean = false
              ): ZIO[Any, K8sFailure, $entityT] = {
                  statusClient.replaceStatus(of, updatedStatus, None, dryRun)
                }
             """,
                q"""
                  override def getStatus(
                  name: String
                ): ZIO[Any, K8sFailure, $entityT] =
                  statusClient.getStatus(name, None)
                 """
              )
            else
              List.empty

          val statusAccessors: List[Defn] =
            if (statusEntity.isDefined)
              List(
                q"""
               def replaceStatus(
                of: $entityT,
                updatedStatus: $statusT,
                dryRun: Boolean = false
              ): ZIO[$typeAliasT, K8sFailure, $entityT] = {
                  ZIO.accessM(_.get.replaceStatus(of, updatedStatus, dryRun))
                }
             """,
                q"""
                  def getStatus(
                  name: String,
                ): ZIO[$typeAliasT, K8sFailure, $entityT] =
                  ZIO.accessM(_.get.getStatus(name))
                 """
              )
            else
              List.empty

          val subresourceImpls =
            subresources.toList
              .sortBy(_.name)
              .flatMap { subresource =>
                val capName = subresource.name.capitalize
                val getTerm = Term.Name(s"get$capName")
                val putTerm = Term.Name(s"replace$capName")
                val postTerm = Term.Name(s"create$capName")

                val clientName = Term.Name(subresource.name + "Client")
                val modelT: Type.Ref = getSubresourceModelType(modelPackageName, subresource)

                subresource.actionVerbs.flatMap {
                  case "get"  =>
                    List(
                      q"""
                        override def $getTerm(
                          name: String,
                        ): ZIO[Any, K8sFailure, $modelT] =
                          $clientName.get(name, None)
                        """
                    )
                  case "put"  =>
                    List(
                      q"""
                          override def $putTerm(
                            name: String,
                            updatedValue: $modelT,
                            dryRun: Boolean = false
                          ): ZIO[Any, K8sFailure, $modelT] =
                            $clientName.replace(name, updatedValue, None, dryRun)
                        """
                    )
                  case "post" =>
                    List(
                      q"""
                          override def $postTerm(
                            value: $modelT,
                            dryRun: Boolean = false
                          ): ZIO[Any, K8sFailure, $modelT] =
                            $clientName.create(value, None, dryRun)
                        """
                    )
                  case _      => List.empty
                }
              }

          val subresourceAccessors: List[Defn] =
            subresources.toList
              .sortBy(_.name)
              .flatMap { subresource =>
                val capName = subresource.name.capitalize
                val getTerm = Term.Name(s"get$capName")
                val putTerm = Term.Name(s"replace$capName")
                val postTerm = Term.Name(s"create$capName")

                val modelT: Type.Ref = getSubresourceModelType(modelPackageName, subresource)

                subresource.actionVerbs.flatMap {
                  case "get"  =>
                    List(
                      q"""
                        def $getTerm(
                          name: String
                        ): ZIO[$typeAliasT, K8sFailure, $modelT] =
                          ZIO.accessM(_.get.$getTerm(name))
                        """
                    )
                  case "put"  =>
                    List(
                      q"""
                          def $putTerm(
                            name: String,
                            updatedValue: $modelT,
                            dryRun: Boolean = false
                          ): ZIO[$typeAliasT, K8sFailure, $modelT] =
                            ZIO.accessM(_.get.$putTerm(name, updatedValue, dryRun))
                        """
                    )
                  case "post" =>
                    List(
                      q"""
                          def $postTerm(
                            value: $modelT,
                            dryRun: Boolean = false
                          ): ZIO[$typeAliasT, K8sFailure, $modelT] =
                            ZIO.accessM(_.get.$postTerm(value, dryRun))
                        """
                    )
                  case _      => List.empty
                }
              }

          val mainInterface = t"ClusterResource[$entityT]"
          val extraInterfaces =
            ((if (statusEntity.isDefined)
                List[Type](t"ClusterResourceStatus[$statusT, $entityT]")
              else Nil) ::
              subresources.toList.map { subresource =>
                List(getClusterSubresourceWrapperType(subresource, entityT))
              }).flatten

          val typeAliasRhs: Type = extraInterfaces.foldLeft[Type](t"Has[$mainInterface]") {
            case (l, r) =>
              Type.With(l, t"Has[$r]")
          }
          val serviceT = Type.Select(typeAliasTerm, Type.Name("Service"))
          val typeAlias = q"""type ${typeAliasT} = ${t"Has[$serviceT]"}"""
          val typeAliasGeneric = q"""type Generic = $typeAliasRhs"""

          val mainInterfaceI = Init(mainInterface, Name.Anonymous(), List.empty)
          val extraInterfaceIs = extraInterfaces.map(t => Init(t, Name.Anonymous(), List.empty))

          val interfacesWrappedInHas =
            extraInterfaces.foldLeft[Term](q"Has[$mainInterface](this)") { case (l, t) =>
              q"$l ++ Has[$t](this)"
            }

          q"""package $basePackage.$ver {

          $entityImport
          import com.coralogix.zio.k8s.model.pkg.apis.meta.v1._
          import com.coralogix.zio.k8s.model._
          import com.coralogix.zio.k8s.client.{Resource, ResourceStatus, ClusterResource, ClusterResourceStatus, K8sFailure}
          import com.coralogix.zio.k8s.client.impl.{ResourceClient, ResourceStatusClient, SubresourceClient}
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

          package object $moduleName {
            $typeAlias

            object $typeAliasTerm {
              $typeAliasGeneric

              trait Service
                extends $mainInterfaceI with ..${extraInterfaceIs} {

                val asGeneric: $typeAliasGenericT = $interfacesWrappedInHas
              }

              final class Live(..$clientList) extends Service {
                override def getAll(
                  chunkSize: Int = 10
                ): ZStream[Any, K8sFailure, $entityT] =
                   client.getAll(None, chunkSize)

                override def watch(
                  resourceVersion: Option[String]
                ): ZStream[Any, K8sFailure, TypedWatchEvent[$entityT]] =
                  client.watch(None, resourceVersion)

                override def watchForever(
                ): ZStream[Clock, K8sFailure, TypedWatchEvent[$entityT]] =
                  client.watchForever(None)

                override def get(
                  name: String
                ): ZIO[Any, K8sFailure, $entityT] =
                  client.get(name, None)

                override def create(
                  newResource: $entityT,
                  dryRun: Boolean = false
                ): ZIO[Any, K8sFailure, $entityT] =
                  client.create(newResource, None, dryRun)

                override def replace(
                  name: String,
                  updatedResource: $entityT,
                  dryRun: Boolean = false
                ): ZIO[Any, K8sFailure, $entityT] =
                 client.replace(name, updatedResource, None, dryRun)

                override def delete(
                  name: String,
                  deleteOptions: DeleteOptions,
                  dryRun: Boolean = false
                ): ZIO[Any, K8sFailure, Status] =
                  client.delete(name, deleteOptions, None, dryRun)

                ..$statusImpls

                ..$subresourceImpls

                ..$clientExposure
              }

              $live
              $any
            }

            def getAll(
              chunkSize: Int = 10
            ): ZStream[$typeAliasT, K8sFailure, $entityT] =
              ZStream.accessStream(_.get.getAll(chunkSize))

            def watch(
              resourceVersion: Option[String]
            ): ZStream[$typeAliasT, K8sFailure, TypedWatchEvent[$entityT]] =
              ZStream.accessStream(_.get.watch(resourceVersion))

            def watchForever(
            ): ZStream[$typeAliasT with Clock, K8sFailure, TypedWatchEvent[$entityT]] =
              ZStream.accessStream(_.get.watchForever())

            def get(
              name: String,
            ): ZIO[$typeAliasT, K8sFailure, $entityT] =
              ZIO.accessM(_.get.get(name))

            def create(
              newResource: $entityT,
              dryRun: Boolean = false
            ): ZIO[$typeAliasT, K8sFailure, $entityT] =
              ZIO.accessM(_.get.create(newResource, dryRun))

            def replace(
              name: String,
              updatedResource: $entityT,
              dryRun: Boolean = false
            ): ZIO[$typeAliasT, K8sFailure, $entityT] =
              ZIO.accessM(_.get.replace(name, updatedResource, dryRun))

            def delete(
              name: String,
              deleteOptions: DeleteOptions,
              dryRun: Boolean = false
            ): ZIO[$typeAliasT, K8sFailure, Status] =
              ZIO.accessM(_.get.delete(name, deleteOptions, dryRun))

            ..$statusAccessors

            ..$subresourceAccessors

            ..$customResourceDefinition

          }
          }
          """
        }

      code.toString
    }

  protected def getClientConstruction(
    modelPackageName: String,
    statusEntity: Option[String],
    subresources: Set[SubresourceId],
    entityT: Type,
    statusT: Type,
    fullyQualifiedSubresourceModels: Boolean = false
  ): List[Term] =
    q"new ResourceClient[$entityT](resourceType, cluster, backend)" ::
      (((if (statusEntity.isDefined)
           List(
             q"new ResourceStatusClient[$statusT, $entityT](resourceType, cluster, backend)"
           )
         else Nil) ::
        subresources.toList.map { subresource =>
          val nameLit = Lit.String(subresource.name)
          val modelT =
            getSubresourceModelType(modelPackageName, subresource, fullyQualifiedSubresourceModels)
          List(q"new SubresourceClient[$modelT](resourceType, cluster, backend, $nameLit)")
        }).flatten)

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

  private def getSubresourceModelType(
    modelPackageName: String,
    subresource: SubresourceId,
    fullyQualified: Boolean = false
  ): Type.Ref = {
    val (modelPkg, modelName) = splitName(subresource.modelName)
    if (modelPkg.nonEmpty) {
      val modelNs =
        (if (fullyQualified)
           modelPackageName + "." + modelPkg.mkString(".")
         else
           modelPkg.mkString(".")).parse[Term].get.asInstanceOf[Term.Ref]
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
