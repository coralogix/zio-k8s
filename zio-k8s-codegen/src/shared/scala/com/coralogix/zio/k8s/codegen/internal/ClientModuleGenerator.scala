package com.coralogix.zio.k8s.codegen.internal

import io.swagger.v3.oas.models.media.ObjectSchema
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.blocking.Blocking
import zio.{ Task, ZIO }
import com.coralogix.zio.k8s.codegen.internal.Conversions.{
  groupNameToPackageName,
  modelRoot,
  splitName
}
import com.coralogix.zio.k8s.codegen.internal.CodegenIO._
import org.atteo.evo.inflector.English
import zio.nio.file.Path
import zio.nio.file.Files

import scala.meta._
import _root_.io.github.vigoo.metagen.core.ScalaType
import zio.prelude.NonEmptyList

trait ClientModuleGenerator {
  this: Common =>

  def generateModuleCode(
    basePackageName: String,
    name: String,
    entity: ScalaType,
    statusEntity: Option[ScalaType],
    deleteResponse: ScalaType,
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

      val typeAlias = ScalaType(entity.pkg, English.plural(entity.name))
      val typeAliasGeneric = typeAlias / "Generic"

      val ver = Term.Name(gvk.version)

      val dtoPackage = entity.pkg.term

      val status = statusEntity.getOrElse(ScalaType.nothing)

      val isStandardDelete = deleteResponse == Types.status

      val deleteResultTerm =
        if (isStandardDelete)
          q"() => com.coralogix.zio.k8s.model.pkg.apis.meta.v1.Status()"
        else
          q"createDeleteResult"

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
            param"client: Resource[${entity.typ}] with ResourceDelete[${entity.typ}, ${deleteResponse.typ}] with ResourceDeleteAll[${entity.typ}]"
          else
            param"client: Resource[${entity.typ}] with ResourceDelete[${entity.typ}, ${deleteResponse.typ}]") :: ((if (
                                                                                                                     statusEntity.isDefined
                                                                                                                   )
                                                                                                                     List[Term.Param](
                                                                                                                       param"statusClient: ResourceStatus[${status.typ}, ${entity.typ}]"
                                                                                                                     )
                                                                                                                   else
                                                                                                                     Nil) ::
          subresources.toList.map { subresource =>
            val clientName = Term.Name(subresource.name + "Client")
            List(param"$clientName: Subresource[${subresource.model.typ}]")
          }).flatten)

      val clientExposure =
        q"override val asGenericResource: Resource[${entity.typ}] = client" ::
          q"override val asGenericResourceDelete: ResourceDelete[${entity.typ}, ${deleteResponse.typ}] = client" ::
          (((if (supportsDeleteMany)
               List(
                 q"override val asGenericResourceDeleteAll: ResourceDeleteAll[${entity.typ}] = client"
               )
             else Nil) ::
            (if (statusEntity.isDefined)
               List(
                 q"override val asGenericResourceStatus: ResourceStatus[${status.typ}, ${entity.typ}] = statusClient"
               )
             else Nil) ::
            subresources.toList.map { subresource =>
              val capName = subresource.name.capitalize
              val clientName = Term.Name(subresource.name + "Client")
              val asGenericTerm = Pat.Var(Term.Name(s"asGeneric${capName}Subresource"))
              List(
                q"override val $asGenericTerm: Subresource[${subresource.model.typ}] = $clientName"
              )
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
        q"""val live: ZLayer[Has[SttpBackend[Task, ZioStreams with WebSockets]] with Has[K8sCluster], Nothing, ${typeAlias.typ}] =
                  ZLayer.fromServices[SttpBackend[Task, ZioStreams with WebSockets], K8sCluster, Service] {
                    (backend: SttpBackend[Task, ZioStreams with WebSockets], cluster: K8sCluster) => {
                      val resourceType = implicitly[ResourceMetadata[${entity.typ}]].resourceType
                      new Live(..$clientConstruction)
                    }
                  }
             """

      val any =
        q"""val any: ZLayer[${typeAlias.typ}, Nothing, ${typeAlias.typ}] = ZLayer.requires[${typeAlias.typ}]"""

      val test =
        if (isStandardDelete) {
          q"""val test: ZLayer[Any, Nothing, ${typeAlias.typ}] =
              ZLayer.fromEffect {
                for {
                  ..${testClientConstruction.map(_._2)}
                } yield new Live(..${testClientConstruction.map(_._1)})
              }
         """
        } else {
          q"""def test(createDeleteResult: () => ${deleteResponse.typ}): ZLayer[Any, Nothing, ${typeAlias.typ}] =
              ZLayer.fromEffect {
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
                of: ${entity.typ},
                updatedStatus: ${status.typ},
                namespace: K8sNamespace,
                dryRun: Boolean = false
              ): ZIO[${typeAlias.typ}, K8sFailure, ${entity.typ}] = {
                  ZIO.accessM(_.get.replaceStatus(of, updatedStatus, namespace, dryRun))
                }
             """,
                q"""
                  def getStatus(
                  name: String,
                  namespace: K8sNamespace
                ): ZIO[${typeAlias.typ}, K8sFailure, ${entity.typ}] =
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
                val model = subresource.model

                subresource.actionVerbs.flatMap {
                  case "get" if subresource.hasStreamingGet =>
                    val paramDefs =
                      param"name: String" :: param"namespace: K8sNamespace" :: subresource.toMethodParameters
                    val params = q"name" :: q"namespace" :: subresource.toParameterAccess
                    List(
                      q"""
                        def $getTerm(
                          ..$paramDefs
                        ): ZStream[${typeAlias.typ}, K8sFailure, ${model.typ}] =
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
                        ): ZIO[${typeAlias.typ}, K8sFailure, ${model.typ}] =
                          ZIO.accessM(_.get.$getTerm(..$params))
                        """
                    )
                  case "put"                                =>
                    List(
                      q"""
                          def $putTerm(
                            name: String,
                            updatedValue: ${model.typ},
                            namespace: K8sNamespace,
                            dryRun: Boolean = false
                          ): ZIO[${typeAlias.typ}, K8sFailure, ${model.typ}] =
                            ZIO.accessM(_.get.$putTerm(name, updatedValue, namespace, dryRun))
                        """
                    )
                  case "post"                               =>
                    List(
                      q"""
                          def $postTerm(
                            name: String,
                            value: ${model.typ},
                            namespace: K8sNamespace,
                            dryRun: Boolean = false
                          ): ZIO[${typeAlias.typ}, K8sFailure, ${model.typ}] =
                            ZIO.accessM(_.get.$postTerm(name, value, namespace, dryRun))
                        """
                    )
                  case _                                    => List.empty
                }
              }

          val mainInterface = t"NamespacedResource[${entity.typ}]"
          val extraInterfaces =
            t"NamespacedResourceDelete[${entity.typ}, ${deleteResponse.typ}]" ::
              ((if (supportsDeleteMany)
                  List[Type](t"NamespacedResourceDeleteAll[${entity.typ}]")
                else Nil) ::
                (if (statusEntity.isDefined)
                   List[Type](t"NamespacedResourceStatus[${status.typ}, ${entity.typ}]")
                 else Nil) ::
                subresources.toList.map { subresource =>
                  List(getNamespacedSubresourceWrapperType(subresource, entity).typ)
                }).flatten

          val typeAliasRhs: Type = extraInterfaces.foldLeft[Type](t"Has[$mainInterface]") {
            case (l, r) =>
              Type.With(l, t"Has[$r]")
          }
          val serviceT = Type.Select(typeAlias.term, Type.Name("Service"))
          val typeAliasQ = q"""type ${typeAlias.typName} = ${t"Has[$serviceT]"}"""
          val typeAliasGenericQ = q"""type Generic = $typeAliasRhs"""

          val mainInterfaceI = Init(mainInterface, Name.Anonymous(), List.empty)
          val extraInterfaceIs = extraInterfaces.map(t => Init(t, Name.Anonymous(), List.empty))

          val interfacesWrappedInHas =
            extraInterfaces.foldLeft[Term](q"Has[$mainInterface](this)") { case (l, t) =>
              q"$l ++ Has[$t](this)"
            }

          q"""package $basePackage.$ver {

          package object $moduleName {
            $typeAliasQ

            object ${typeAlias.termName} {
              $typeAliasGenericQ

              trait Service
                extends $mainInterfaceI with ..$extraInterfaceIs {

                val asGeneric: ${typeAliasGeneric.typ} = $interfacesWrappedInHas
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
            ): ZStream[${typeAlias.typ}, K8sFailure, ${entity.typ}] =
              ZStream.accessStream(_.get.getAll(namespace, chunkSize, fieldSelector, labelSelector, resourceVersion))

            def watch(
              namespace: Option[K8sNamespace],
              resourceVersion: Option[String],
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
            ): ZStream[${typeAlias.typ}, K8sFailure, TypedWatchEvent[${entity.typ}]] =
              ZStream.accessStream(_.get.watch(namespace, resourceVersion, fieldSelector, labelSelector))

            def watchForever(
              namespace: Option[K8sNamespace],
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
            ): ZStream[${typeAlias.typ} with Clock, K8sFailure, TypedWatchEvent[${entity.typ}]] =
              ZStream.accessStream(_.get.watchForever(namespace, fieldSelector, labelSelector))

            def get(
              name: String,
              namespace: K8sNamespace
            ): ZIO[${typeAlias.typ}, K8sFailure, ${entity.typ}] =
              ZIO.accessM(_.get.get(name, namespace))

            def create(
              newResource: ${entity.typ},
              namespace: K8sNamespace,
              dryRun: Boolean = false
            ): ZIO[${typeAlias.typ}, K8sFailure, ${entity.typ}] =
              ZIO.accessM(_.get.create(newResource, namespace, dryRun))

            def replace(
              name: String,
              updatedResource: ${entity.typ},
              namespace: K8sNamespace,
              dryRun: Boolean = false
            ): ZIO[${typeAlias.typ}, K8sFailure, ${entity.typ}] =
              ZIO.accessM(_.get.replace(name, updatedResource, namespace, dryRun))

            def delete(
              name: String,
              deleteOptions: DeleteOptions,
              namespace: K8sNamespace,
              dryRun: Boolean = false,
              gracePeriod: Option[Duration] = None,
              propagationPolicy: Option[PropagationPolicy] = None
            ): ZIO[${typeAlias.typ}, K8sFailure, ${deleteResponse.typ}] =
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
                  ): ZIO[${typeAlias.typ}, K8sFailure, Status] =
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
                of: ${entity.typ},
                updatedStatus: ${status.typ},
                dryRun: Boolean = false
              ): ZIO[${typeAlias.typ}, K8sFailure, ${entity.typ}] = {
                  ZIO.accessM(_.get.replaceStatus(of, updatedStatus, dryRun))
                }
             """,
                q"""
                  def getStatus(
                  name: String,
                ): ZIO[${typeAlias.typ}, K8sFailure, ${entity.typ}] =
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
                val model = subresource.model

                subresource.actionVerbs.flatMap {
                  case "get" if subresource.hasStreamingGet =>
                    val paramDefs = param"name: String" :: subresource.toMethodParameters
                    val params = q"name" :: subresource.toParameterAccess
                    List(
                      q"""
                        def $getTerm(
                          ..$paramDefs
                        ): ZStream[${typeAlias.typ}, K8sFailure, ${model.typ}] =
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
                        ): ZIO[${typeAlias.typ}, K8sFailure, ${model.typ}] =
                          ZIO.accessM(_.get.$getTerm(..$params))
                        """
                    )
                  case "put"                                =>
                    List(
                      q"""
                          def $putTerm(
                            name: String,
                            updatedValue: ${model.typ},
                            dryRun: Boolean = false
                          ): ZIO[${typeAlias.typ}, K8sFailure, ${model.typ}] =
                            ZIO.accessM(_.get.$putTerm(name, updatedValue, dryRun))
                        """
                    )
                  case "post"                               =>
                    List(
                      q"""
                          def $postTerm(
                            value: ${model.typ},
                            dryRun: Boolean = false
                          ): ZIO[${typeAlias.typ}, K8sFailure, ${model.typ}] =
                            ZIO.accessM(_.get.$postTerm(value, dryRun))
                        """
                    )
                  case _                                    => List.empty
                }
              }

          val mainInterface = t"ClusterResource[${entity.typ}]"
          val extraInterfaces =
            t"ClusterResourceDelete[${entity.typ}, ${deleteResponse.typ}]" ::
              ((if (supportsDeleteMany)
                  List[Type](t"ClusterResourceDeleteAll[${entity.typ}]")
                else Nil) ::
                (if (statusEntity.isDefined)
                   List[Type](t"ClusterResourceStatus[${status.typ}, ${entity.typ}]")
                 else Nil) ::
                subresources.toList.map { subresource =>
                  List(getClusterSubresourceWrapperType(subresource, entity).typ)
                }).flatten

          val typeAliasRhs: Type = extraInterfaces.foldLeft[Type](t"Has[$mainInterface]") {
            case (l, r) =>
              Type.With(l, t"Has[$r]")
          }
          val serviceT = Type.Select(typeAlias.term, Type.Name("Service"))
          val typeAliasQ = q"""type ${typeAlias.typName} = ${t"Has[$serviceT]"}"""
          val typeAliasGenericQ = q"""type Generic = $typeAliasRhs"""

          val mainInterfaceI = Init(mainInterface, Name.Anonymous(), List.empty)
          val extraInterfaceIs = extraInterfaces.map(t => Init(t, Name.Anonymous(), List.empty))

          val interfacesWrappedInHas =
            extraInterfaces.foldLeft[Term](q"Has[$mainInterface](this)") { case (l, t) =>
              q"$l ++ Has[$t](this)"
            }

          q"""package $basePackage.$ver {

          package object $moduleName {
            $typeAliasQ

            object ${typeAlias.termName} {
              $typeAliasGenericQ

              trait Service
                extends $mainInterfaceI with ..$extraInterfaceIs {

                val asGeneric: ${typeAliasGeneric.typ} = $interfacesWrappedInHas
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
            ): ZStream[${typeAlias.typ}, K8sFailure, ${entity.typ}] =
              ZStream.accessStream(_.get.getAll(chunkSize, fieldSelector, labelSelector, resourceVersion))

            def watch(
              resourceVersion: Option[String],
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
            ): ZStream[${typeAlias.typ}, K8sFailure, TypedWatchEvent[${entity.typ}]] =
              ZStream.accessStream(_.get.watch(resourceVersion, fieldSelector, labelSelector))

            def watchForever(
              fieldSelector: Option[FieldSelector] = None,
              labelSelector: Option[LabelSelector] = None,
            ): ZStream[${typeAlias.typ} with Clock, K8sFailure, TypedWatchEvent[${entity.typ}]] =
              ZStream.accessStream(_.get.watchForever(fieldSelector, labelSelector))

            def get(
              name: String,
            ): ZIO[${typeAlias.typ}, K8sFailure, ${entity.typ}] =
              ZIO.accessM(_.get.get(name))

            def create(
              newResource: ${entity.typ},
              dryRun: Boolean = false
            ): ZIO[${typeAlias.typ}, K8sFailure, ${entity.typ}] =
              ZIO.accessM(_.get.create(newResource, dryRun))

            def replace(
              name: String,
              updatedResource: ${entity.typ},
              dryRun: Boolean = false
            ): ZIO[${typeAlias.typ}, K8sFailure, ${entity.typ}] =
              ZIO.accessM(_.get.replace(name, updatedResource, dryRun))

            def delete(
              name: String,
              deleteOptions: DeleteOptions,
              dryRun: Boolean = false,
              gracePeriod: Option[Duration] = None,
              propagationPolicy: Option[PropagationPolicy] = None
            ): ZIO[${typeAlias.typ}, K8sFailure, ${deleteResponse.typ}] =
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
    hasStatus: Boolean,
    subresources: Set[SubresourceId],
    entity: ScalaType,
    status: ScalaType,
    deleteResult: ScalaType
  ): List[Term] =
    q"new ResourceClient[${entity.typ}, ${deleteResult.typ}](resourceType, cluster, backend)" ::
      (((if (hasStatus)
           List(
             q"new ResourceStatusClient[${status.typ}, ${entity.typ}](resourceType, cluster, backend)"
           )
         else Nil) ::
        subresources.toList.map { subresource =>
          val nameLit = Lit.String(subresource.name)
          List(
            q"new SubresourceClient[${subresource.model.typ}](resourceType, cluster, backend, $nameLit)"
          )
        }).flatten)

  protected def getTestClientConstruction(
    hasStatus: Boolean,
    subresources: Set[SubresourceId],
    entity: ScalaType,
    status: ScalaType,
    deleteResult: ScalaType
  ): List[(Term, Enumerator)] = {
    def create(name: String, expr: Term) =
      Term.Name(name) -> Enumerator.Val(Pat.Var(Term.Name(name)), expr)
    def createM(name: String, expr: Term) =
      Term.Name(name) -> Enumerator.Generator(Pat.Var(Term.Name(name)), expr)

    createM(
      "client",
      q"TestResourceClient.make[${entity.typ}, ${deleteResult.typ}](${deleteResult.term})"
    ) ::
      (((if (hasStatus)
           List(
             create("statusClient", q"new TestResourceStatusClient(client)")
           )
         else Nil) ::
        subresources.toList.map { subresource =>
          val name = subresource.name + "Client"
          List(createM(name, q"TestSubresourceClient.make[${subresource.model.typ}]"))
        }).flatten)
  }

  private def getSubresourceWrapperType(
    subresource: SubresourceId,
    wrapperName: String,
    entity: ScalaType
  ): ScalaType = ScalaType(
    Packages.k8sSubresources / subresource.model.pkg.dropPrefix(modelRoot),
    wrapperName,
    entity
  )

  private def getNamespacedSubresourceWrapperType(
    subresource: SubresourceId,
    entity: ScalaType
  ): ScalaType = {
    val capName = subresource.name.capitalize
    val wrapperName = s"Namespaced${capName}Subresource"
    getSubresourceWrapperType(subresource, wrapperName, entity)
  }

  private def getClusterSubresourceWrapperType(
    subresource: SubresourceId,
    entity: ScalaType
  ): ScalaType = {
    val capName = subresource.name.capitalize
    val wrapperName = s"Cluster${capName}Subresource"
    getSubresourceWrapperType(subresource, wrapperName, entity)
  }
}
