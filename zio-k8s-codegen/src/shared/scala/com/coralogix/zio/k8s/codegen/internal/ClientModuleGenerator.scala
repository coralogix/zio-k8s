package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.Conversions.{ groupNameToPackageName, modelRoot }
import _root_.io.github.vigoo.metagen.core.{ CodeFileGenerator, Package, ScalaType }
import org.atteo.evo.inflector.English
import zio.nio.file.Path
import zio.{ Has, ZIO }

import scala.meta._

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
  ): ZIO[Has[CodeFileGenerator], Throwable, Term.Block] =
    ZIO.effect {
      val typeAlias = ScalaType(pkg / name, English.plural(entity.name))
      val typeAliasGeneric = typeAlias / "Generic"

      val status = statusEntity.getOrElse(ScalaType.nothing)

      val isStandardDelete = deleteResponse == Types.status

      val customResourceDefinition: List[Defn] =
        crdYaml match {
          case Some(yamlPath) =>
            val yamlPathLit = Lit.String("/" + yamlPath.toString)

            List(q"""
              val customResourceDefinition: zio.ZIO[zio.blocking.Blocking, Throwable, com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition] =
                for {
                  rawYaml <- ${Types.zstream_.term}.fromInputStream(getClass.getResourceAsStream($yamlPathLit))
                    .transduce(zio.stream.ZTransducer.utf8Decode)
                    .fold("")(_ ++ _).orDie
                  crd <- zio.ZIO.fromEither(io.circe.yaml.parser.parse(rawYaml).flatMap(_.as[com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition]))
                } yield crd
             """)
          case None           =>
            List.empty
        }

      val clientList =
        ((if (supportsDeleteMany)
            param"client: ${Types.resource(entity).typ} with ${Types
              .resourceDelete(entity, deleteResponse)
              .typ} with ${Types.resourceDeleteAll(entity).typ}"
          else
            param"client: ${Types.resource(entity).typ} with ${Types
              .resourceDelete(entity, deleteResponse)
              .typ}") :: ((if (statusEntity.isDefined)
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
              List(
                q"override val $asGenericTerm: ${Types.subresource(subresource.model).typ} = $clientName"
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
        q"""val live: zio.ZLayer[zio.Has[sttp.client3.SttpBackend[zio.Task, sttp.capabilities.zio.ZioStreams with sttp.capabilities.WebSockets]] with ${Types
          .has(Types.k8sCluster)
          .typ}, Nothing, ${typeAlias.typ}] =
                  zio.ZLayer.fromServices[sttp.client3.SttpBackend[zio.Task, sttp.capabilities.zio.ZioStreams with sttp.capabilities.WebSockets], ${Types.k8sCluster.typ}, Service] {
                    (backend: SttpBackend[zio.Task, sttp.capabilities.zio.ZioStreams with sttp.capabilities.WebSockets], cluster: K8sCluster) => {
                      val resourceType = implicitly[${Types
          .resourceMetadata(entity)
          .typ}].resourceType
                      new Live(..$clientConstruction)
                    }
                  }
             """

      val any =
        q"""val any: ${Types
          .zlayer(typeAlias, ScalaType.nothing, typeAlias)
          .typ} = ${Types.zlayer_.term}.requires[${typeAlias.typ}]"""

      val test =
        if (isStandardDelete) {
          q"""val test: ${Types.zlayer(ScalaType.any, ScalaType.nothing, typeAlias).typ}  =
              ${Types.zlayer_.term}.fromEffect {
                for {
                  ..${testClientConstruction.map(_._2)}
                } yield new Live(..${testClientConstruction.map(_._1)})
              }
         """
        } else {
          q"""def test(createDeleteResult: () => ${deleteResponse.typ}): ZLayer[Any, Nothing, ${typeAlias.typ}] =
              ${Types.zlayer_.term}.fromEffect {
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
                    deleteOptions: ${Types.deleteOptions.typ},
                    namespace: ${Types.k8sNamespace.typ},
                    dryRun: Boolean = false,
                    gracePeriod: ${ScalaType.option(Types.duration).typ} = None,
                    propagationPolicy: ${ScalaType.option(Types.propagationPolicy).typ} = None,
                    fieldSelector: ${ScalaType.option(Types.fieldSelector).typ} = None,
                    labelSelector: ${ScalaType.option(Types.labelSelector).typ} = None
                  ): ZIO[${typeAlias.typ}, ${Types.k8sFailure.typ}, Status] =
                    ${Types.zio_.term}.accessM(_.get.deleteAll(deleteOptions, namespace, dryRun, gracePeriod, propagationPolicy, fieldSelector, labelSelector))
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
                namespace: ${Types.k8sNamespace.typ},
                dryRun: Boolean = false
              ): ${Types.zio(typeAlias, Types.k8sFailure, entity).typ} = {
                  ${Types.zio_.term}.accessM(_.get.replaceStatus(of, updatedStatus, namespace, dryRun))
                }
             """,
                q"""
                  def getStatus(
                  name: String,
                  namespace: ${Types.k8sNamespace.typ}
                ): ${Types.zio(typeAlias, Types.k8sFailure, entity).typ} =
                  ${Types.zio_.term}.accessM(_.get.getStatus(name, namespace))
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
                      param"name: String" :: param"namespace: ${Types.k8sNamespace.typ}" :: subresource.toMethodParameters
                    val params = q"name" :: q"namespace" :: subresource.toParameterAccess
                    List(
                      q"""
                        def $getTerm(
                          ..$paramDefs
                        ): ZStream[${typeAlias.typ}, ${Types.k8sFailure.typ}, ${model.typ}] =
                          ZStream.accessStream(_.get.$getTerm(..$params))
                        """
                    )
                  case "get"                                =>
                    val paramDefs =
                      param"name: String" :: param"namespace: ${Types.k8sNamespace.typ}" :: subresource.toMethodParameters
                    val params = q"name" :: q"namespace" :: subresource.toParameterAccess
                    List(
                      q"""
                        def $getTerm(
                          ..$paramDefs
                        ): ${Types.zio(typeAlias, Types.k8sFailure, model).typ} =
                          ${Types.zio_.term}.accessM(_.get.$getTerm(..$params))
                        """
                    )
                  case "put"                                =>
                    List(
                      q"""
                          def $putTerm(
                            name: String,
                            updatedValue: ${model.typ},
                            namespace: ${Types.k8sNamespace.typ},
                            dryRun: Boolean = false
                          ): ${Types.zio(typeAlias, Types.k8sFailure, model).typ} =
                            ${Types.zio_.term}.accessM(_.get.$putTerm(name, updatedValue, namespace, dryRun))
                        """
                    )
                  case "post"                               =>
                    List(
                      q"""
                          def $postTerm(
                            name: String,
                            value: ${model.typ},
                            namespace: ${Types.k8sNamespace.typ},
                            dryRun: Boolean = false
                          ): ${Types.zio(typeAlias, Types.k8sFailure, model).typ} =
                            ${Types.zio_.term}.accessM(_.get.$postTerm(name, value, namespace, dryRun))
                        """
                    )
                  case _                                    => List.empty
                }
              }

          val mainInterface = Types.namespacedResource(entity).typ
          val extraInterfaces = Types.namespacedResourceDelete(entity, deleteResponse).typ ::
            ((if (supportsDeleteMany)
                List[Type](Types.namespacedResourceDeleteAll(entity).typ)
              else Nil) ::
              (if (statusEntity.isDefined)
                 List[Type](Types.namespacedResourceStatus(status, entity).typ)
               else Nil) ::
              subresources.toList.map { subresource =>
                List(getNamespacedSubresourceWrapperType(subresource, entity).typ)
              }).flatten

          val typeAliasRhs: Type = extraInterfaces.foldLeft[Type](t"zio.Has[$mainInterface]") {
            case (l, r) =>
              Type.With(l, t"zio.Has[$r]")
          }
          val serviceT = Type.Select(typeAlias.term, Type.Name("Service"))
          val typeAliasQ = q"""type ${typeAlias.typName} = ${t"zio.Has[$serviceT]"}"""
          val typeAliasGenericQ = q"""type Generic = $typeAliasRhs"""

          val mainInterfaceI = Init(mainInterface, Name.Anonymous(), List.empty)
          val extraInterfaceIs = extraInterfaces.map(t => Init(t, Name.Anonymous(), List.empty))

          val interfacesWrappedInHas =
            extraInterfaces.foldLeft[Term](q"zio.Has[$mainInterface](this)") { case (l, t) =>
              q"$l ++ zio.Has[$t](this)"
            }

          val defs = List(
            typeAliasQ,
            q"""object ${typeAlias.termName} {
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
            }""",
            q"""def getAll(
                            namespace: ${ScalaType.option(Types.k8sNamespace).typ},
                            chunkSize: Int = 10,
                            fieldSelector: ${ScalaType.option(Types.fieldSelector).typ} = None,
                            labelSelector: ${ScalaType.option(Types.labelSelector).typ} = None,
                            resourceVersion: ${Types.listResourceVersion.typ} = ${Types.listResourceVersion.term}.MostRecent
                          ): ${Types.zstream(typeAlias, Types.k8sFailure, entity).typ} =
            ${Types.zstream_.term}.accessStream(_.get.getAll(namespace, chunkSize, fieldSelector, labelSelector, resourceVersion))""",
            q"""def watch(
                     namespace: ${ScalaType.option(Types.k8sNamespace).typ},
                     resourceVersion: ${ScalaType.option(ScalaType.string).typ},
                     fieldSelector: ${ScalaType.option(Types.fieldSelector).typ} = None,
                     labelSelector: ${ScalaType.option(Types.labelSelector).typ} = None,
                   ): ${Types
              .zstream(typeAlias, Types.k8sFailure, Types.typedWatchEvent(entity))
              .typ} =
            ZStream.accessStream(_.get.watch(namespace, resourceVersion, fieldSelector, labelSelector))""",
            q"""def watchForever(
                            namespace: ${ScalaType.option(Types.k8sNamespace).typ},
                            fieldSelector: ${ScalaType.option(Types.fieldSelector).typ} = None,
                            labelSelector: ${ScalaType.option(Types.labelSelector).typ} = None,
                          ): zio.stream.ZStream[${typeAlias.typ} with zio.clock.Clock, ${Types.k8sFailure.typ}, ${Types
              .typedWatchEvent(entity)
              .typ}] =
              ${Types.zstream_.term}.accessStream(_.get.watchForever(namespace, fieldSelector, labelSelector))""",
            q"""def get(
                   name: String,
                   namespace: ${Types.k8sNamespace.typ}
                 ): ${Types.zio(typeAlias, Types.k8sFailure, entity).typ} =
            ${Types.zio_.term}.accessM(_.get.get(name, namespace))""",
            q"""def create(
                      newResource: ${entity.typ},
                      namespace: ${Types.k8sNamespace.typ},
                      dryRun: Boolean = false
                    ): ${Types.zio(typeAlias, Types.k8sFailure, entity).typ} =
            ${Types.zio_.term}.accessM(_.get.create(newResource, namespace, dryRun))""",
            q"""def replace(
                       name: String,
                       updatedResource: ${entity.typ},
                       namespace: ${Types.k8sNamespace.typ},
                       dryRun: Boolean = false
                     ): ${Types.zio(typeAlias, Types.k8sFailure, entity).typ} =
            ${Types.zio_.term}.accessM(_.get.replace(name, updatedResource, namespace, dryRun))""",
            q"""def delete(
                      name: String,
                      deleteOptions: ${Types.deleteOptions.typ},
                      namespace: ${Types.k8sNamespace.typ},
                      dryRun: Boolean = false,
                      gracePeriod: ${ScalaType.option(Types.duration).typ} = None,
                      propagationPolicy: ${ScalaType.option(Types.propagationPolicy).typ} = None
                    ): ZIO[${typeAlias.typ}, ${Types.k8sFailure.typ}, ${deleteResponse.typ}] =
            ${Types.zio_.term}.accessM(_.get.delete(name, deleteOptions, namespace, dryRun, gracePeriod, propagationPolicy))"""
          ) ++
            deleteManyAccessors ++
            statusAccessors ++
            subresourceAccessors ++
            customResourceDefinition

          Term.Block(defs)

        } else {
          // CLUSTER RESOURCE

          val deleteManyAccessors: List[Defn] =
            if (supportsDeleteMany) {
              List(
                q"""
                  def deleteAll(
                    deleteOptions: ${Types.deleteOptions.typ},
                    dryRun: Boolean = false,
                    gracePeriod: ${ScalaType.option(Types.duration).typ} = None,
                    propagationPolicy: ${ScalaType.option(Types.propagationPolicy).typ} = None,
                    fieldSelector: ${ScalaType.option(Types.fieldSelector).typ} = None,
                    labelSelector: ${ScalaType.option(Types.labelSelector).typ} = None
                  ): ZIO[${typeAlias.typ}, ${Types.k8sFailure.typ}, Status] =
                    ${Types.zio_.term}.accessM(_.get.deleteAll(deleteOptions, dryRun, gracePeriod, propagationPolicy, fieldSelector, labelSelector))
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
              ): ${Types.zio(typeAlias, Types.k8sFailure, entity).typ} = {
                  ${Types.zio_.term}.accessM(_.get.replaceStatus(of, updatedStatus, dryRun))
                }
             """,
                q"""
                  def getStatus(
                  name: String,
                ): ${Types.zio(typeAlias, Types.k8sFailure, entity).typ} =
                  ${Types.zio_.term}.accessM(_.get.getStatus(name))
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
                        ): ${Types.zstream(typeAlias, Types.k8sFailure, model).typ} =
                          ${Types.zstream_.term}.accessStream(_.get.$getTerm(..$params))
                        """
                    )
                  case "get"                                =>
                    val paramDefs = param"name: String" :: subresource.toMethodParameters
                    val params = q"name" :: subresource.toParameterAccess
                    List(
                      q"""
                        def $getTerm(
                          ..$paramDefs
                        ): ${Types.zio(typeAlias, Types.k8sFailure, model).typ} =
                          ${Types.zio_.term}.accessM(_.get.$getTerm(..$params))
                        """
                    )
                  case "put"                                =>
                    List(
                      q"""
                          def $putTerm(
                            name: String,
                            updatedValue: ${model.typ},
                            dryRun: Boolean = false
                          ): ${Types.zio(typeAlias, Types.k8sFailure, model).typ} =
                            ${Types.zio_.term}.accessM(_.get.$putTerm(name, updatedValue, dryRun))
                        """
                    )
                  case "post"                               =>
                    List(
                      q"""
                          def $postTerm(
                            value: ${model.typ},
                            dryRun: Boolean = false
                          ): ${Types.zio(typeAlias, Types.k8sFailure, model).typ} =
                            ${Types.zio_.term}.accessM(_.get.$postTerm(value, dryRun))
                        """
                    )
                  case _                                    => List.empty
                }
              }

          val mainInterface = Types.clusterResource(entity).typ
          val extraInterfaces =
            Types.clusterResourceDelete(entity, deleteResponse).typ ::
              ((if (supportsDeleteMany)
                  List[Type](Types.clusterResourceDeleteAll(entity).typ)
                else Nil) ::
                (if (statusEntity.isDefined)
                   List[Type](Types.clusterResourceStatus(status, entity).typ)
                 else Nil) ::
                subresources.toList.map { subresource =>
                  List(getClusterSubresourceWrapperType(subresource, entity).typ)
                }).flatten

          val typeAliasRhs: Type = extraInterfaces.foldLeft[Type](t"zio.Has[$mainInterface]") {
            case (l, r) =>
              Type.With(l, t"zio.Has[$r]")
          }
          val serviceT = Type.Select(typeAlias.term, Type.Name("Service"))
          val typeAliasQ: Stat = q"""type ${typeAlias.typName} = ${t"zio.Has[$serviceT]"}"""
          val typeAliasGenericQ = q"""type Generic = $typeAliasRhs"""

          val mainInterfaceI = Init(mainInterface, Name.Anonymous(), List.empty)
          val extraInterfaceIs = extraInterfaces.map(t => Init(t, Name.Anonymous(), List.empty))

          val interfacesWrappedInHas =
            extraInterfaces.foldLeft[Term](q"zio.Has[$mainInterface](this)") { case (l, t) =>
              q"$l ++ zio.Has[$t](this)"
            }

          val defs = List(
            typeAliasQ,
            q"""
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
            }""",
            q"""def getAll(
              chunkSize: Int = 10,
              fieldSelector: ${ScalaType.option(Types.fieldSelector).typ} = None,
              labelSelector: ${ScalaType.option(Types.labelSelector).typ} = None,
              resourceVersion: ${Types.listResourceVersion.typ} = ${Types.listResourceVersion.term}.MostRecent
            ): ${Types.zstream(typeAlias, Types.k8sFailure, entity).typ} =
              ${Types.zstream_.term}.accessStream(_.get.getAll(chunkSize, fieldSelector, labelSelector, resourceVersion))""",
            q"""def watch(
              resourceVersion: ${ScalaType.option(ScalaType.string).typ},
              fieldSelector: ${ScalaType.option(Types.fieldSelector).typ} = None,
              labelSelector: ${ScalaType.option(Types.labelSelector).typ} = None,
            ): ${Types.zstream(typeAlias, Types.k8sFailure, Types.typedWatchEvent(entity)).typ} =
              ${Types.zstream_.term}.accessStream(_.get.watch(resourceVersion, fieldSelector, labelSelector))""",
            q"""def watchForever(
              fieldSelector: ${ScalaType.option(Types.fieldSelector).typ} = None,
              labelSelector: ${ScalaType.option(Types.labelSelector).typ} = None,
            ): zio.stream.ZStream[${typeAlias.typ} with zio.clock.Clock, ${Types.k8sFailure.typ}, ${Types
              .typedWatchEvent(entity)
              .typ}] =
              ${Types.zstream_.term}.accessStream(_.get.watchForever(fieldSelector, labelSelector))""",
            q"""def get(
              name: String,
            ): ${Types.zio(typeAlias, Types.k8sFailure, entity).typ} =
              ${Types.zio_.term}.accessM(_.get.get(name))""",
            q"""def create(
              newResource: ${entity.typ},
              dryRun: Boolean = false
            ): ${Types.zio(typeAlias, Types.k8sFailure, entity).typ} =
              ${Types.zio_.term}.accessM(_.get.create(newResource, dryRun))""",
            q"""def replace(
              name: String,
              updatedResource: ${entity.typ},
              dryRun: Boolean = false
            ): ${Types.zio(typeAlias, Types.k8sFailure, entity).typ} =
              ${Types.zio_.term}.accessM(_.get.replace(name, updatedResource, dryRun))""",
            q"""def delete(
              name: String,
              deleteOptions: ${Types.deleteOptions.typ},
              dryRun: Boolean = false,
              gracePeriod: ${ScalaType.option(Types.duration).typ} = None,
              propagationPolicy: ${ScalaType.option(Types.propagationPolicy).typ} = None
            ): ${Types.zio(typeAlias, Types.k8sFailure, deleteResponse).typ} =
              ${Types.zio_.term}.accessM(_.get.delete(name, deleteOptions, dryRun, gracePeriod, propagationPolicy))"""
          ) ++
            deleteManyAccessors ++
            statusAccessors ++
            subresourceAccessors ++
            customResourceDefinition

          Term.Block(defs)
        }

      code
    }

  protected def getClientConstruction(
    hasStatus: Boolean,
    subresources: Set[SubresourceId],
    entity: ScalaType,
    status: ScalaType,
    deleteResult: ScalaType
  ): List[Term] =
    q"new ${Types.resourceClient(entity, deleteResult).typ}(resourceType, cluster, backend)" ::
      (((if (hasStatus)
           List(
             q"new ${Types.resourceStatusClient(status, entity).typ}(resourceType, cluster, backend)"
           )
         else Nil) ::
        subresources.toList.map { subresource =>
          val nameLit = Lit.String(subresource.name)
          List(
            q"new ${Types.subresourceClient(subresource.model).typ}(resourceType, cluster, backend, $nameLit)"
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
      q"${Types.testResourceClient.term}.make[${entity.typ}, ${deleteResult.typ}](() => ${deleteResult.term}())"
    ) ::
      (((if (hasStatus)
           List(
             create("statusClient", q"new ${Types.testResourceStatusClient.typ}(client)")
           )
         else Nil) ::
        subresources.toList.map { subresource =>
          val name = subresource.name + "Client"
          List(createM(name, q"${Types.testSubresourceClient.term}.make[${subresource.model.typ}]"))
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
