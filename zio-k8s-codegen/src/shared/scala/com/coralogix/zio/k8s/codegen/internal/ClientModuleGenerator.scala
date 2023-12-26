package com.coralogix.zio.k8s.codegen.internal

import io.github.vigoo.metagen.core.*
import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.{Task, ZIO}
import com.coralogix.zio.k8s.codegen.internal.Conversions.{groupNameToPackageName, splitName, splitNameOld}
import com.coralogix.zio.k8s.codegen.internal.CodegenIO.*
import org.atteo.evo.inflector.English
import zio.nio.file.Path
import zio.nio.file.Files

import scala.meta.*

trait ClientModuleGenerator {
  this: Common =>

  def generateModuleCode(
    pkg: Package,
    name: String,
    entity: ScalaType,
    statusEntity: Option[ScalaType],
    deleteResponse: ScalaType,
    gvk: GroupVersionKind,
    isNamespaced: Boolean,
    subresources: Set[SubresourceId],
    crdYaml: Option[Path],
    supportsDeleteMany: Boolean
  ): ZIO[CodeFileGenerator, Throwable, Term.Block] =
    ZIO.attempt {
      val typeAlias = ScalaType(pkg / name, English.plural(entity.name))
      val typeAliasGeneric = typeAlias / "Generic"

      val status = statusEntity.getOrElse(ScalaType.nothing)

      val isStandardDelete = deleteResponse == Types.status

      val customResourceDefinition: List[Defn] =
        crdYaml match {
          case Some(yamlPath) =>
            val yamlPathLit = Lit.String("/" + yamlPath.toString)

            List(q"""
              val customResourceDefinition: ZIO[Any, Throwable, com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition] =
                for {
                  rawYaml <- ZStream.fromInputStream(getClass.getResourceAsStream($yamlPathLit))
                    .via(ZPipeline.fromChannel(ZPipeline.utf8Decode.channel.orDie))
                    .runFold("")(_ ++ _).orDie
                  crd <- ZIO.fromEither(_root_.io.circe.yaml.parser.parse(rawYaml).flatMap(_.as[com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition]))
                } yield crd
             """)
          case None           =>
            List.empty
        }

      val clientList =
        ((if (supportsDeleteMany)
            param"client: ${Types.resource(entity).typ} with ${Types.resourceDelete(entity, deleteResponse).typ} with ${Types.resourceDeleteAll(entity).typ}"
          else
            param"client: ${Types.resource(entity).typ} with ${Types.resourceDelete(entity, deleteResponse).typ}") :: ((if (
                                                                                                    statusEntity.isDefined
                                                                                                  )
                                                                                                    List[Term.Param](
                                                                                                      param"statusClient: ${Types.resourceStatus(status, entity).typ}"
                                                                                                    )
                                                                                                  else
                                                                                                    Nil) ::
          subresources.toList.map { subresource =>
            val clientName = Term.Name(subresource.name + "Client")
            List(param"$clientName: ${Types.subresource(subresource.model).typ}")
          }).flatten)

      val clientExposure =
        q"override val asGenericResource: ${Types.resource(entity).typ} = client" ::
          q"override val asGenericResourceDelete: ${Types.resourceDelete(entity, deleteResponse).typ} = client" ::
          (((if (supportsDeleteMany)
               List(
                 q"override val asGenericResourceDeleteAll: ${Types.resourceDeleteAll(entity).typ} = client"
               )
             else Nil) ::
            (if (statusEntity.isDefined)
               List(
                 q"override val asGenericResourceStatus: ${Types.resourceStatus(status, entity).typ} = statusClient"
               )
             else Nil) ::
            subresources.toList.map { subresource =>
              val capName = subresource.name.capitalize
              val clientName = Term.Name(subresource.name + "Client")
              val asGenericTerm = Pat.Var(Term.Name(s"asGeneric${capName}Subresource"))
              List(q"override val $asGenericTerm: ${Types.subresource(subresource.model).typ} = $clientName")
            }).flatten)

      val clientConstruction: List[Term] =
        getClientConstruction(
          statusEntity.isDefined,
          subresources,
          entity,
          status,
          deleteResponse
        )
      val testClientConstruction: List[(Term, Enumerator)] =
        getTestClientConstruction(
          statusEntity.isDefined,
          subresources,
          entity,
          status,
          deleteResponse
        )

      val live =
        q"""val live: ZLayer[SttpBackend[Task, ZioStreams with WebSockets] with K8sCluster, Nothing, ${typeAlias.typ}] =
              ZLayer.fromZIO {
                for {
                  backend <- ZIO.service[SttpBackend[Task, ZioStreams with WebSockets]]
                  cluster <- ZIO.service[K8sCluster]
                } yield {
                    val resourceType = implicitly[${Types.resourceMetadata(entity).typ}].resourceType
                    new Live(..$clientConstruction)
                }
              }
             """

      val any =
        q"""val any: ZLayer[${typeAlias.typ}, Nothing, ${typeAlias.typ}] = ZLayer.environment[${typeAlias.typ}]"""

      val test =
        if (isStandardDelete) {
          q"""val test: ZLayer[Any, Nothing, ${typeAlias.typ}] =
              ZLayer.fromZIO {
                for {
                  ..${testClientConstruction.map(_._2)}
                } yield new Live(..${testClientConstruction.map(_._1)})
              }
         """
        } else {
          q"""def test(createDeleteResult: () => ${deleteResponse.typ}): ZLayer[Any, Nothing, ${typeAlias.typ}] =
              ZLayer.fromZIO {
                for {
                  ..${testClientConstruction.map(_._2)}
                } yield new Live(..${testClientConstruction.map(_._1)})
              }
          """
        }

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
                  ): ZIO[${typeAlias.typ}, K8sFailure, Status] =
                    ZIO.environmentWithZIO(_.get.deleteAll(deleteOptions, namespace, dryRun, gracePeriod, propagationPolicy, fieldSelector, labelSelector))
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
                  ZIO.environmentWithZIO(_.get.replaceStatus(of, updatedStatus, namespace, dryRun))
                }
             """,
                q"""
                  def getStatus(
                  name: String,
                  namespace: K8sNamespace
                ): ZIO[$typeAliasT, K8sFailure, $entityT] =
                  ZIO.environmentWithZIO(_.get.getStatus(name, namespace))
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
                          ZStream.environmentWithStream(_.get.$getTerm(..$params))
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
                          ZIO.environmentWithZIO(_.get.$getTerm(..$params))
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
                            ZIO.environmentWithZIO[$typeAliasT](_.get.$putTerm(name, updatedValue, namespace, dryRun))
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
                            ZIO.environmentWithZIO[$typeAliasT](_.get.$postTerm(name, value, namespace, dryRun))
                        """
                    )
                  case _                                    => List.empty
                }
              }

          val mainInterface = t"NamespacedResource[$entityT]"
          val extraInterfaces =
            t"NamespacedResourceDelete[$entityT, $deleteResultT]" ::
              ((if (supportsDeleteMany)
                  List[Type](t"NamespacedResourceDeleteAll[$entityT]")
                else Nil) ::
                (if (statusEntity.isDefined)
                   List[Type](t"NamespacedResourceStatus[$statusT, $entityT]")
                 else Nil) ::
                subresources.toList.map { subresource =>
                  List(getNamespacedSubresourceWrapperType(subresource, entityT))
                }).flatten

          val typeAliasRhs: Type = extraInterfaces.foldLeft[Type](t"$mainInterface") {
            case (l, r) =>
              Type.With(l, t"$r")
          }
          val serviceT = Type.Select(typeAliasTerm, Type.Name("Service"))
          val typeAlias = q"""type $typeAliasT = ${t"$serviceT"}"""
          val typeAliasGeneric = q"""type Generic = $typeAliasRhs"""

          val mainInterfaceI = Init(mainInterface, Name.Anonymous(), Seq.empty)
          val extraInterfaceIs = extraInterfaces.map(t => Init(t, Name.Anonymous(), Seq.empty))

          val interfacesWrappedInEnv =
            extraInterfaces.foldLeft[Term](q"ZEnvironment[$mainInterface](this)") { case (l, t) =>
              q"$l ++ ZEnvironment[$t](this)"
            }

          val emptyMod: List[Mod] = Nil

          q"""package $basePackage.$ver {

          $entityImport
          import com.coralogix.zio.k8s.model.pkg.apis.meta.v1._
          import com.coralogix.zio.k8s.model._
          import com.coralogix.zio.k8s.client.{Resource, ResourceDelete, ResourceDeleteAll, ResourceStatus, Subresource, NamespacedResource, NamespacedResourceDelete, NamespacedResourceDeleteAll, NamespacedResourceStatus, K8sFailure, CodingFailure}
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
          import zio.stream.{ZStream, ZPipeline}
          import zio._

          package object $moduleName {
            object $typeAliasTerm {
              trait Service
                extends $mainInterfaceI with ..$extraInterfaceIs {

                val asGeneric: ZEnvironment[$typeAliasGenericT] = ($interfacesWrappedInEnv)
              }

              final class Live(..$clientList) extends Service {
                ..$clientExposure
              }

              $typeAliasGeneric
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
              ZStream.environmentWithStream[$typeAliasT](_.get.getAll(namespace, chunkSize, fieldSelector, labelSelector, resourceVersion))

            def watch(
              namespace: Option[K8sNamespace],
              resourceVersion: Option[String],
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
            ): ZStream[$typeAliasT, K8sFailure, TypedWatchEvent[$entityT]] =
              ZStream.environmentWithStream[$typeAliasT](_.get.watch(namespace, resourceVersion, fieldSelector, labelSelector))

            def watchForever(
              namespace: Option[K8sNamespace],
              resourceVersion: Option[String] = None,
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
            ): ZStream[$typeAliasT, K8sFailure, TypedWatchEvent[$entityT]] =
              ZStream.environmentWithStream[$typeAliasT](_.get.watchForever(namespace, resourceVersion, fieldSelector, labelSelector))

            def get(
              name: String,
              namespace: K8sNamespace
            ): ZIO[$typeAliasT, K8sFailure, $entityT] =
              ZIO.environmentWithZIO[$typeAliasT](_.get.get(name, namespace))

            def create(
              newResource: $entityT,
              namespace: K8sNamespace,
              dryRun: Boolean = false
            ): ZIO[$typeAliasT, K8sFailure, $entityT] =
              ZIO.environmentWithZIO[$typeAliasT](_.get.create(newResource, namespace, dryRun))

            def replace(
              name: String,
              updatedResource: $entityT,
              namespace: K8sNamespace,
              dryRun: Boolean = false
            ): ZIO[$typeAliasT, K8sFailure, $entityT] =
              ZIO.environmentWithZIO[$typeAliasT](_.get.replace(name, updatedResource, namespace, dryRun))

            def delete(
              name: String,
              deleteOptions: DeleteOptions,
              namespace: K8sNamespace,
              dryRun: Boolean = false,
              gracePeriod: Option[Duration] = None,
              propagationPolicy: Option[PropagationPolicy] = None
            ): ZIO[$typeAliasT, K8sFailure, $deleteResultT] =
              ZIO.environmentWithZIO[$typeAliasT](_.get.delete(name, deleteOptions, namespace, dryRun, gracePeriod, propagationPolicy))

            ..$deleteManyAccessors

            ..$statusAccessors

            ..$subresourceAccessors

            ..$customResourceDefinition

            $typeAlias
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
                    ZIO.environmentWithZIO[$typeAliasT](_.get.deleteAll(deleteOptions, dryRun, gracePeriod, propagationPolicy, fieldSelector, labelSelector))
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
                  ZIO.environmentWithZIO[$typeAliasT](_.get.replaceStatus(of, updatedStatus, dryRun))
                }
             """,
                q"""
                  def getStatus(
                  name: String,
                ): ZIO[$typeAliasT, K8sFailure, $entityT] =
                  ZIO.environmentWithZIO[$typeAliasT](_.get.getStatus(name))
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
                          ZStream.environmentWithStream[$typeAliasT](_.get.$getTerm(..$params))
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
                          ZIO.environmentWithZIO[$typeAliasT](_.get.$getTerm(..$params))
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
                            ZIO.environmentWithZIO[$typeAliasT](_.get.$putTerm(name, updatedValue, dryRun))
                        """
                    )
                  case "post"                               =>
                    List(
                      q"""
                          def $postTerm(
                            value: $modelT,
                            dryRun: Boolean = false
                          ): ZIO[$typeAliasT, K8sFailure, $modelT] =
                            ZIO.environmentWithZIO[$typeAliasT](_.get.$postTerm(value, dryRun))
                        """
                    )
                  case _                                    => List.empty
                }
              }

          val mainInterface = t"ClusterResource[$entityT]"
          val extraInterfaces =
            t"ClusterResourceDelete[$entityT, $deleteResultT]" ::
              ((if (supportsDeleteMany)
                  List[Type](t"ClusterResourceDeleteAll[$entityT]")
                else Nil) ::
                (if (statusEntity.isDefined)
                   List[Type](t"ClusterResourceStatus[$statusT, $entityT]")
                 else Nil) ::
                subresources.toList.map { subresource =>
                  List(getClusterSubresourceWrapperType(subresource, entityT))
                }).flatten

          val typeAliasRhs: Type = extraInterfaces.foldLeft[Type](t"$mainInterface") {
            case (l, r) =>
              Type.With(l, t"$r")
          }
          val serviceT = Type.Select(typeAliasTerm, Type.Name("Service"))
          val typeAlias = q"""type $typeAliasT = ${t"$serviceT"}"""
          val typeAliasGeneric = q"""type Generic = $typeAliasRhs"""

          val mainInterfaceI = Init(mainInterface, Name.Anonymous(), Seq.empty)
          val extraInterfaceIs = extraInterfaces.map(t => Init(t, Name.Anonymous(), Seq.empty))

          val interfacesWrappedInEnv =
            extraInterfaces.foldLeft[Term](q"ZEnvironment[$mainInterface](this)") { case (l, t) =>
              q"$l ++ ZEnvironment[$t](this)"
            }

          q"""package $basePackage.$ver {

          $entityImport
          import com.coralogix.zio.k8s.model.pkg.apis.meta.v1._
          import com.coralogix.zio.k8s.model._
          import com.coralogix.zio.k8s.client.{Resource, ResourceDelete, ResourceDeleteAll, ResourceStatus, Subresource, ClusterResource, ClusterResourceDelete, ClusterResourceDeleteAll, ClusterResourceStatus, K8sFailure, CodingFailure}
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
          import zio.stream.{ZStream, ZPipeline}
          import zio._

          package object $moduleName {
            object $typeAliasTerm {

              trait Service
                extends $mainInterfaceI with ..$extraInterfaceIs {

                val asGeneric: ZEnvironment[$typeAliasGenericT] = ($interfacesWrappedInEnv)
              }

              final class Live(..$clientList) extends Service {
                ..$clientExposure
              }

              $typeAliasGeneric
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
              ZStream.environmentWithStream[$typeAliasT](_.get.getAll(chunkSize, fieldSelector, labelSelector, resourceVersion))

            def watch(
              resourceVersion: Option[String],
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
            ): ZStream[$typeAliasT, K8sFailure, TypedWatchEvent[$entityT]] =
              ZStream.environmentWithStream[$typeAliasT](_.get.watch(resourceVersion, fieldSelector, labelSelector))

            def watchForever(
              resourceVersion: Option[String]= None,
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
            ): ZStream[$typeAliasT, K8sFailure, TypedWatchEvent[$entityT]] =
              ZStream.environmentWithStream[$typeAliasT](_.get.watchForever(resourceVersion, fieldSelector, labelSelector))

            def get(
              name: String,
            ): ZIO[$typeAliasT, K8sFailure, $entityT] =
              ZIO.environmentWithZIO[$typeAliasT](_.get.get(name))

            def create(
              newResource: $entityT,
              dryRun: Boolean = false
            ): ZIO[$typeAliasT, K8sFailure, $entityT] =
              ZIO.environmentWithZIO[$typeAliasT](_.get.create(newResource, dryRun))

            def replace(
              name: String,
              updatedResource: $entityT,
              dryRun: Boolean = false
            ): ZIO[$typeAliasT, K8sFailure, $entityT] =
              ZIO.environmentWithZIO[$typeAliasT](_.get.replace(name, updatedResource, dryRun))

            def delete(
              name: String,
              deleteOptions: DeleteOptions,
              dryRun: Boolean = false,
              gracePeriod: Option[Duration] = None,
              propagationPolicy: Option[PropagationPolicy] = None
            ): ZIO[$typeAliasT, K8sFailure, $deleteResultT] =
              ZIO.environmentWithZIO[$typeAliasT](_.get.delete(name, deleteOptions, dryRun, gracePeriod, propagationPolicy))

            ..$deleteManyAccessors

            ..$statusAccessors

            ..$subresourceAccessors

            ..$customResourceDefinition

            $typeAlias
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
    deleteResultT: Type,
    fullyQualifiedSubresourceModels: Boolean = false
  ): List[Term] =
    q"new ResourceClient[$entityT, $deleteResultT](resourceType, cluster, backend)" ::
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
    deleteResultT: Type,
    deleteResultTerm: Term,
    fullyQualifiedSubresourceModels: Boolean = false
  ): List[(Term, Enumerator)] = {
    def create(name: String, expr: Term) =
      Term.Name(name) -> Enumerator.Val(Pat.Var(Term.Name(name)), expr)
    def createM(name: String, expr: Term) =
      Term.Name(name) -> Enumerator.Generator(Pat.Var(Term.Name(name)), expr)

    createM(
      "client",
      q"TestResourceClient.make[$entityT, $deleteResultT]($deleteResultTerm)"
    ) ::
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
    val (modelPkg, modelName) = splitNameOld(subresource.modelName)
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
    val (modelPkg, _) = splitNameOld(subresource.modelName)
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
