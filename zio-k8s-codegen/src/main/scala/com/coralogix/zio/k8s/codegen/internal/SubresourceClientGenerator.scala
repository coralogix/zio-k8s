package com.coralogix.zio.k8s.codegen.internal

import io.github.vigoo.metagen.core.*
import zio.ZIO
import zio.nio.file.Path
import zio.prelude.NonEmptyList

import scala.meta.*

trait SubresourceClientGenerator {
  this: ModelGenerator & Common =>

  def generateSubresourceAliases(
    subresources: Set[SubresourceId]
  ): ZIO[Generator, GeneratorFailure[Nothing], Set[Path]] =
    ZIO.foreach(subresources) { subid =>
      for {
        targetPath <-
          Generator.generateScalaPackage[Any, Nothing](subresourcePackage(subid), subid.name) {
            subresourceCode(subid)
          }
      } yield targetPath
    }

  private def subresourcePackage(subresource: SubresourceId): Package =
    new Package(
      NonEmptyList(
        "com",
        "coralogix",
        "zio",
        "k8s",
        "client",
        "subresources"
      ) ++ subresource.model.pkg.dropPrefix(modelRootPkg).path
    )

  def subresourceCode(
    subresource: SubresourceId
  ): ZIO[CodeFileGenerator, Nothing, Term.Block] = {
    val pkg = subresourcePackage(subresource)
    val capName = subresource.name.capitalize
    val namespacedT = Type.Name(s"Namespaced${capName}Subresource")
    val namespacedTerm = Term.Name(s"Namespaced${capName}Subresource")
    val clusterT = Type.Name(s"Cluster${capName}Subresource")
    val clusterTerm = Term.Name(s"Cluster${capName}Subresource")
    val model = subresource.model

    val getTerm = Term.Name(s"get$capName")
    val putTerm = Term.Name(s"replace$capName")
    val postTerm = Term.Name(s"create$capName")
    val asGenericTerm = Term.Name(s"asGeneric${capName}Subresource")

    val nameLit = Lit.String(subresource.name)

    val clusterDefs = subresource.actionVerbs.toList.flatMap {
      case "get" if subresource.hasStreamingGet =>
        val params = param"name: String" :: subresource.toMethodParameters
        val customParamsMap = subresource.toMapFromParameters
        List(q"""
          def $getTerm(..$params): ${Types.zstream(ScalaType.any, Types.k8sFailure, model).typ} =
            $asGenericTerm.streamingGet(name, None, ${subresource.streamingGetTransducer}, $customParamsMap)
          """)
      case "get"                                =>
        val params = param"name: String" :: subresource.toMethodParameters
        val customParamsMap = subresource.toMapFromParameters
        List(q"""
          def $getTerm(..$params): ${Types.zio(ScalaType.any, Types.k8sFailure, model).typ} =
            $asGenericTerm.get(name, None, $customParamsMap)
          """)
      case "put"                                =>
        List(q"""
          def $putTerm(name: String,
                             updatedValue: ${model.typ},
                             dryRun: Boolean = false
                            ): ${Types.zio(ScalaType.any, Types.k8sFailure, model).typ} =
            $asGenericTerm.replace(name, updatedValue, None, dryRun)
           """)
      case "post"                               =>
        List(q"""
           def $postTerm(name: String,
                         value: ${model.typ},
                         dryRun: Boolean = false): ${Types
            .zio(ScalaType.any, Types.k8sFailure, model)
            .typ} =
             $asGenericTerm.create(name, value, None, dryRun)
         """)
      case _                                    => List.empty
    }

    val namespacedDefs = subresource.actionVerbs.toList.flatMap {
      case "get" if subresource.hasStreamingGet =>
        val params =
          param"name: String" :: param"namespace: K8sNamespace" :: subresource.toMethodParameters
        val customParamsMap = subresource.toMapFromParameters
        List(q"""
          def $getTerm(..$params): ${Types.zstream(ScalaType.any, Types.k8sFailure, model).typ} =
            $asGenericTerm.streamingGet(name, Some(namespace), ${subresource.streamingGetTransducer}, $customParamsMap)
          """)
      case "get"                                =>
        val params =
          param"name: String" :: param"namespace: K8sNamespace" :: subresource.toMethodParameters
        val customParamsMap = subresource.toMapFromParameters
        List(q"""
          def $getTerm(..$params): ${Types.zio(ScalaType.any, Types.k8sFailure, model).typ} =
            $asGenericTerm.get(name, Some(namespace), $customParamsMap)
          """)
      case "put"                                =>
        List(q"""
          def $putTerm(name: String,
                             updatedValue: ${model.typ},
                             namespace: K8sNamespace,
                             dryRun: Boolean = false
                            ): ${Types.zio(ScalaType.any, Types.k8sFailure, model).typ} =
             $asGenericTerm.replace(name, updatedValue, Some(namespace), dryRun)
           """)
      case "post"                               =>
        List(q"""
           def $postTerm(name: String,
                         value: ${model.typ},
                         namespace: K8sNamespace,
                         dryRun: Boolean = false): ${Types
            .zio(ScalaType.any, Types.k8sFailure, model)
            .typ} =
             $asGenericTerm.create(name, value, Some(namespace), dryRun)
         """)
      case _                                    => List.empty
    }

    prettyPrint(q"""
        trait $namespacedT[T] {
          val $asGenericTerm: Subresource[${model.typ}]

          ..$namespacedDefs
        }

        object $namespacedTerm {
          def makeClient[T : EnvironmentTag : ResourceMetadata](backend: SttpBackend[Task, ZioStreams with WebSockets], cluster: K8sCluster): SubresourceClient[${model.typ}] =
            new SubresourceClient[${model.typ}](implicitly[ResourceMetadata[T]].resourceType, cluster, backend, $nameLit)
        }

        trait $clusterT[T] {
          val $asGenericTerm: Subresource[${model.typ}]

          ..$clusterDefs
        }

        object $clusterTerm {
          def makeClient[T : EnvironmentTag : ResourceMetadata](backend: SttpBackend[Task, ZioStreams with WebSockets], cluster: K8sCluster): SubresourceClient[${model.typ}] =
            new SubresourceClient[${model.typ}](implicitly[ResourceMetadata[T]].resourceType, cluster, backend, $nameLit)
        }
        }
     """)
  }
}
