package com.coralogix.zio.k8s.codegen.internal

import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.blocking.Blocking
import zio.{ Task, ZIO }
import com.coralogix.zio.k8s.codegen.internal.Conversions.{ groupNameToPackageName, splitName }
import com.coralogix.zio.k8s.codegen.internal.CodegenIO._
import org.atteo.evo.inflector.English
import zio.nio.core.file.Path
import zio.nio.file.Files

import scala.meta._

trait ClientModuleGenerator {
  this: Common =>

  def generateModuleCode(
    basePackageName: String,
    modelPackageName: String,
    name: String,
    entity: String,
    statusEntity: Option[String],
    gvk: GroupVersionKind,
    isNamespaced: Boolean,
    subresources: Set[SubresourceId],
    crdYaml: Option[Path],
    supportsDeleteMany: Boolean
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

      val pluralEntity = English.plural(entity)

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
      val typeAliasT = Type.Name(pluralEntity)
      val typeAliasTerm = Term.Name(pluralEntity)
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
        ((if (supportsDeleteMany)
            param"client: Resource[$entityT] with ResourceDeleteAll[$entityT]"
          else
            param"client: Resource[$entityT]") :: ((if (statusEntity.isDefined)
                                                      List[Term.Param](
                                                        param"statusClient: ResourceStatus[$statusT, $entityT]"
                                                      )
                                                    else Nil) ::
          subresources.toList.map { subresource =>
            val clientName = Term.Name(subresource.name + "Client")
            val modelT = getSubresourceModelType(modelPackageName, subresource)
            List(param"$clientName: Subresource[$modelT]")
          }).flatten)

      val clientExposure =
        q"override val asGenericResource: Resource[$entityT] = client" ::
          (((if (supportsDeleteMany)
               List(
                 q"override val asGenericResourceDeleteAll: ResourceDeleteAll[$entityT] = client"
               )
             else Nil) ::
            (if (statusEntity.isDefined)
               List(
                 q"override val asGenericResourceStatus: ResourceStatus[$statusT, $entityT] = statusClient"
               )
             else Nil) ::
            subresources.toList.map { subresource =>
              val capName = subresource.name.capitalize
              val clientName = Term.Name(subresource.name + "Client")
              val asGenericTerm = Pat.Var(Term.Name(s"asGeneric${capName}Subresource"))
              val modelT = getSubresourceModelType(modelPackageName, subresource)
              List(q"override val $asGenericTerm: Subresource[$modelT] = $clientName")
            }).flatten)

      val clientConstruction: List[Term] =
        getClientConstruction(modelPackageName, statusEntity, subresources, entityT, statusT)
      val testClientConstruction: List[(Term, Enumerator)] =
        getTestClientConstruction(modelPackageName, statusEntity, subresources, entityT, statusT)

      val live =
        q"""val live: ZLayer[Has[SttpBackend[Task, ZioStreams with WebSockets]] with Has[K8sCluster], Nothing, $typeAliasT] =
                  ZLayer.fromServices[SttpBackend[Task, ZioStreams with WebSockets], K8sCluster, Service] {
                    (backend: SttpBackend[Task, ZioStreams with WebSockets], cluster: K8sCluster) => {
                      val resourceType = implicitly[ResourceMetadata[$entityT]].resourceType
                      new Live(..$clientConstruction)
                    }
                  }
             """

      val any =
        q"""val any: ZLayer[$typeAliasT, Nothing, $typeAliasT] = ZLayer.requires[$typeAliasT]"""

      val test =
        q"""val test: ZLayer[Any, Nothing, $typeAliasT] =
              ZLayer.fromEffect {
                for {
                  ..${testClientConstruction.map(_._2)}
                } yield new Live(..${testClientConstruction.map(_._1)})
              }
         """

      val code =
        if (isNamespaced) {
          // NAMESPACED RESOURCE

          val deleteManyAccessors: List[Defn] =
            if (supportsDeleteMany)
              List(
                q"""
                 def deleteAll(
                    deleteOptions: DeleteOptions,
                    namespace: K8sNamespace,
                    dryRun: Boolean = false,
                    gracePeriod: Option[Duration] = None,
                    propagationPolicy: Option[PropagationPolicy] = None,
                    fieldSelector: Option[FieldSelector] = None,
                    labelSelector: Option[LabelSelector] = None
                  ): ZIO[$typeAliasT, K8sFailure, Status] =
                    ZIO.accessM(_.get.deleteAll(deleteOptions, namespace, dryRun, gracePeriod, propagationPolicy, fieldSelector, labelSelector))
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
                  case "get" if subresource.hasStreamingGet =>
                    val paramDefs =
                      param"name: String" :: param"namespace: K8sNamespace" :: subresource.toMethodParameters
                    val params = q"name" :: q"namespace" :: subresource.toParameterAccess
                    List(
                      q"""
                        def $getTerm(
                          ..$paramDefs
                        ): ZStream[$typeAliasT, K8sFailure, $modelT] =
                          ZStream.accessStream(_.get.$getTerm(..$params))
                        """
                    )
                  case "get"                                =>
                    val paramDefs =
                      param"name: String" :: param"namespace: K8sNamespace" :: subresource.toMethodParameters
                    val params = q"name" :: q"namespace" :: subresource.toParameterAccess
                    List(
                      q"""
                        def $getTerm(
                          ..$paramDefs
                        ): ZIO[$typeAliasT, K8sFailure, $modelT] =
                          ZIO.accessM(_.get.$getTerm(..$params))
                        """
                    )
                  case "put"                                =>
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
                  case "post"                               =>
                    List(
                      q"""
                          def $postTerm(
                            name: String,
                            value: $modelT,
                            namespace: K8sNamespace,
                            dryRun: Boolean = false
                          ): ZIO[$typeAliasT, K8sFailure, $modelT] =
                            ZIO.accessM(_.get.$postTerm(name, value, namespace, dryRun))
                        """
                    )
                  case _                                    => List.empty
                }
              }

          val mainInterface = t"NamespacedResource[$entityT]"
          val extraInterfaces =
            ((if (supportsDeleteMany)
                List[Type](t"NamespacedResourceDeleteAll[$entityT]")
              else Nil) ::
              (if (statusEntity.isDefined)
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
          import com.coralogix.zio.k8s.client.{Resource, ResourceDeleteAll, ResourceStatus, Subresource, NamespacedResource, NamespacedResourceDeleteAll, NamespacedResourceStatus, K8sFailure}
          import com.coralogix.zio.k8s.client.impl.{ResourceClient, ResourceStatusClient, SubresourceClient}
          import com.coralogix.zio.k8s.client.test.{TestResourceClient, TestResourceStatusClient, TestSubresourceClient}
          import com.coralogix.zio.k8s.client.model.{
            FieldSelector,
            K8sCluster,
            K8sNamespace,
            K8sResourceType,
            LabelSelector,
            ListResourceVersion,
            PropagationPolicy,
            ResourceMetadata,
            TypedWatchEvent
          }
          import sttp.capabilities.WebSockets
          import sttp.capabilities.zio.ZioStreams
          import sttp.client3.SttpBackend
          import zio.blocking.Blocking
          import zio.clock.Clock
          import zio.duration.Duration
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
                ..$clientExposure
              }

              $live
              $any
              $test
            }

            def getAll(
              namespace: Option[K8sNamespace],
              chunkSize: Int = 10,
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
              resourceVersion: ListResourceVersion = ListResourceVersion.MostRecent
            ): ZStream[$typeAliasT, K8sFailure, $entityT] =
              ZStream.accessStream(_.get.getAll(namespace, chunkSize, fieldSelector, labelSelector, resourceVersion))

            def watch(
              namespace: Option[K8sNamespace],
              resourceVersion: Option[String],
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
            ): ZStream[$typeAliasT, K8sFailure, TypedWatchEvent[$entityT]] =
              ZStream.accessStream(_.get.watch(namespace, resourceVersion, fieldSelector, labelSelector))

            def watchForever(
              namespace: Option[K8sNamespace],
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
            ): ZStream[$typeAliasT with Clock, K8sFailure, TypedWatchEvent[$entityT]] =
              ZStream.accessStream(_.get.watchForever(namespace, fieldSelector, labelSelector))

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
              dryRun: Boolean = false,
              gracePeriod: Option[Duration] = None,
              propagationPolicy: Option[PropagationPolicy] = None
            ): ZIO[$typeAliasT, K8sFailure, Status] =
              ZIO.accessM(_.get.delete(name, deleteOptions, namespace, dryRun, gracePeriod, propagationPolicy))

            ..$deleteManyAccessors

            ..$statusAccessors

            ..$subresourceAccessors

            ..$customResourceDefinition

          }
          }
          """
        } else {
          // CLUSTER RESOURCE

          val deleteManyAccessors: List[Defn] =
            if (supportsDeleteMany) {
              List(
                q"""
                  def deleteAll(
                    deleteOptions: DeleteOptions,
                    dryRun: Boolean = false,
                    gracePeriod: Option[Duration] = None,
                    propagationPolicy: Option[PropagationPolicy] = None,
                    fieldSelector: Option[FieldSelector] = None,
                    labelSelector: Option[LabelSelector] = None
                  ): ZIO[$typeAliasT, K8sFailure, Status] =
                    ZIO.accessM(_.get.deleteAll(deleteOptions, dryRun, gracePeriod, propagationPolicy, fieldSelector, labelSelector))
               """
              )
            } else {
              List.empty
            }

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
                  case "get" if subresource.hasStreamingGet =>
                    val paramDefs = param"name: String" :: subresource.toMethodParameters
                    val params = q"name" :: subresource.toParameterAccess
                    List(
                      q"""
                        def $getTerm(
                          ..$paramDefs
                        ): ZStream[$typeAliasT, K8sFailure, $modelT] =
                          ZStream.accessStream(_.get.$getTerm(..$params))
                        """
                    )
                  case "get"                                =>
                    val paramDefs = param"name: String" :: subresource.toMethodParameters
                    val params = q"name" :: subresource.toParameterAccess
                    List(
                      q"""
                        def $getTerm(
                          ..$paramDefs
                        ): ZIO[$typeAliasT, K8sFailure, $modelT] =
                          ZIO.accessM(_.get.$getTerm(..$params))
                        """
                    )
                  case "put"                                =>
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
                  case "post"                               =>
                    List(
                      q"""
                          def $postTerm(
                            value: $modelT,
                            dryRun: Boolean = false
                          ): ZIO[$typeAliasT, K8sFailure, $modelT] =
                            ZIO.accessM(_.get.$postTerm(value, dryRun))
                        """
                    )
                  case _                                    => List.empty
                }
              }

          val mainInterface = t"ClusterResource[$entityT]"
          val extraInterfaces =
            ((if (supportsDeleteMany)
                List[Type](t"ClusterResourceDeleteAll[$entityT]")
              else Nil) ::
              (if (statusEntity.isDefined)
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
          import com.coralogix.zio.k8s.client.{Resource, ResourceDeleteAll, ResourceStatus, Subresource, ClusterResource, ClusterResourceDeleteAll, ClusterResourceStatus, K8sFailure}
          import com.coralogix.zio.k8s.client.impl.{ResourceClient, ResourceStatusClient, SubresourceClient}
          import com.coralogix.zio.k8s.client.test.{TestResourceClient, TestResourceStatusClient, TestSubresourceClient}
          import com.coralogix.zio.k8s.client.model.{
            FieldSelector,
            K8sCluster,
            K8sNamespace,
            K8sResourceType,
            LabelSelector,
            ListResourceVersion,
            PropagationPolicy,
            ResourceMetadata,
            TypedWatchEvent
          }
          import sttp.capabilities.WebSockets
          import sttp.capabilities.zio.ZioStreams
          import sttp.client3.SttpBackend
          import zio.blocking.Blocking
          import zio.clock.Clock
          import zio.duration.Duration
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
                ..$clientExposure
              }

              $live
              $any
              $test
            }

            def getAll(
              chunkSize: Int = 10,
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
              resourceVersion: ListResourceVersion = ListResourceVersion.MostRecent
            ): ZStream[$typeAliasT, K8sFailure, $entityT] =
              ZStream.accessStream(_.get.getAll(chunkSize, fieldSelector, labelSelector, resourceVersion))

            def watch(
              resourceVersion: Option[String],
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
            ): ZStream[$typeAliasT, K8sFailure, TypedWatchEvent[$entityT]] =
              ZStream.accessStream(_.get.watch(resourceVersion, fieldSelector, labelSelector))

            def watchForever(
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
            ): ZStream[$typeAliasT with Clock, K8sFailure, TypedWatchEvent[$entityT]] =
              ZStream.accessStream(_.get.watchForever(fieldSelector, labelSelector))

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
              dryRun: Boolean = false,
              gracePeriod: Option[Duration] = None,
              propagationPolicy: Option[PropagationPolicy] = None
            ): ZIO[$typeAliasT, K8sFailure, Status] =
              ZIO.accessM(_.get.delete(name, deleteOptions, dryRun, gracePeriod, propagationPolicy))

            ..$deleteManyAccessors

            ..$statusAccessors

            ..$subresourceAccessors

            ..$customResourceDefinition

          }
          }
          """
        }

      prettyPrint(code)
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

  protected def getTestClientConstruction(
    modelPackageName: String,
    statusEntity: Option[String],
    subresources: Set[SubresourceId],
    entityT: Type,
    statusT: Type,
    fullyQualifiedSubresourceModels: Boolean = false
  ): List[(Term, Enumerator)] = {
    def create(name: String, expr: Term) =
      Term.Name(name) -> Enumerator.Val(Pat.Var(Term.Name(name)), expr)
    def createM(name: String, expr: Term) =
      Term.Name(name) -> Enumerator.Generator(Pat.Var(Term.Name(name)), expr)

    createM("client", q"TestResourceClient.make[$entityT]") ::
      (((if (statusEntity.isDefined)
           List(
             create("statusClient", q"new TestResourceStatusClient(client)")
           )
         else Nil) ::
        subresources.toList.map { subresource =>
          val name = subresource.name + "Client"
          val modelT =
            getSubresourceModelType(modelPackageName, subresource, fullyQualifiedSubresourceModels)
          List(createM(name, q"TestSubresourceClient.make[$modelT]"))
        }).flatten)
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
}
