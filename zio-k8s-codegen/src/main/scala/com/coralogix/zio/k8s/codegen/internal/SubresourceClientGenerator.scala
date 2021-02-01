package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.{ format, writeTextFile }
import com.coralogix.zio.k8s.codegen.internal.Conversions.splitName
import org.scalafmt.interfaces.Scalafmt
import zio.blocking.Blocking
import zio.{ Task, ZIO }
import zio.nio.core.file.Path
import zio.nio.file.Files

import scala.meta._

trait SubresourceClientGenerator {
  this: ModelGenerator =>

  case class SubresourceId(name: String, modelName: String, actionVerbs: Set[String])

  object SubresourceId {
    def fromSubresource(subresource: Subresource): SubresourceId =
      SubresourceId(subresource.name, subresource.modelName, subresource.actions.map(_.action))
  }

  def generateSubresourceAliases(
    scalafmt: Scalafmt,
    targetRoot: Path,
    subresources: Set[Subresource]
  ): ZIO[Blocking, Throwable, Set[Path]] =
    for {
      _        <- Task.effect(
                    logger.info(s"Detected subresources:\n${subresources.map(_.describe).mkString("\n")}")
                  )
      targetDir = targetRoot / "com" / "coralogix" / "zio" / "k8s" / "client" / "subresources"
      subids    = subresources.map(SubresourceId.fromSubresource)
      result   <- ZIO.foreach(subids) { subid =>
                    val (modelPkg, modelName) = splitName(subid.modelName)
                    val src = subresourceSource(subid, modelPkg, modelName)
                    val targetPkgDir = modelPkg.foldLeft(targetDir)(_ / _)
                    val targetPath = targetPkgDir / (subid.name + ".scala")
                    for {
                      _ <- Files.createDirectories(targetPkgDir)
                      _ <- writeTextFile(targetPath, src)
                      _ <- format(scalafmt, targetPath)
                    } yield targetPath
                  }
    } yield result

  def subresourceSource(
    subresource: SubresourceId,
    pkg: Vector[String],
    modelName: String
  ): String = {
    val packageTerm = (Vector("com", "coralogix", "zio", "k8s", "client", "subresources") ++ pkg)
      .mkString(".")
      .parse[Term]
      .get
      .asInstanceOf[Term.Ref]
    val capName = subresource.name.capitalize
    val namespacedT = Type.Name(s"Namespaced${capName}Subresource")
    val namespacedTT = t"$namespacedT[T]"
    val namespacedTerm = Term.Name(s"Namespaced${capName}Subresource")
    val clusterT = Type.Name(s"Cluster${capName}Subresource")
    val clusterTT = t"$clusterT[T]"
    val clusterTerm = Term.Name(s"Cluster${capName}Subresource")
    val modelT =
      if (pkg.nonEmpty) {
        val modelNs = pkg.mkString(".").parse[Term].get.asInstanceOf[Term.Ref]
        Type.Select(modelNs, Type.Name(modelName))
      } else {
        Type.Name(modelName)
      }

    val getTerm = Term.Name(s"get$capName")
    val putTerm = Term.Name(s"replace$capName")
    val postTerm = Term.Name(s"create$capName")

    val nameLit = Lit.String(subresource.name)

    val clusterWrappers = subresource.actionVerbs.toList.flatMap {
      case "get"  =>
        List(q"""
          final def $getTerm(name: String): ZIO[Any, K8sFailure, $modelT] =
            asGenericSubresource.get(name, None)
          """)
      case "put"  =>
        List(q"""
          final def $putTerm(name: String,
                             updatedValue: $modelT,
                             dryRun: Boolean = false
                            ): IO[K8sFailure, $modelT] =
            asGenericSubresource.replace(name, updatedValue, None, dryRun)
           """)
      case "post" =>
        List(q"""
           final def $postTerm(value: $modelT,
                         dryRun: Boolean = false): IO[K8sFailure, $modelT] =
            asGenericSubresource.create(value, None, dryRun)
         """)
      case _      => List.empty
    }

    val namespacedWrappers = subresource.actionVerbs.toList.flatMap {
      case "get"  =>
        List(q"""
          final def $getTerm(name: String, namespace: K8sNamespace): ZIO[Any, K8sFailure, $modelT] =
            asGenericSubresource.get(name, Some(namespace))
          """)
      case "put"  =>
        List(q"""
          final def $putTerm(name: String,
                             updatedValue: $modelT,
                             namespace: K8sNamespace,
                             dryRun: Boolean = false
                            ): IO[K8sFailure, $modelT] =
            asGenericSubresource.replace(name, updatedValue, Some(namespace), dryRun)
           """)
      case "post" =>
        List(q"""
           final def $postTerm(value: $modelT,
                         namespace: K8sNamespace,
                         dryRun: Boolean = false): IO[K8sFailure, $modelT] =
            asGenericSubresource.create(value, Some(namespace), dryRun)
         """)
      case _      => List.empty
    }

    q"""package $packageTerm {

        import com.coralogix.zio.k8s.model._
        import com.coralogix.zio.k8s.client.K8sFailure
        import com.coralogix.zio.k8s.client.model.{K8sCluster, K8sNamespace, ResourceMetadata}
        import com.coralogix.zio.k8s.client.SubresourceClient
        import sttp.client3.httpclient.zio.SttpClient
        import zio._

        class $namespacedT[T](asGenericSubresource: SubresourceClient[$modelT]) {
          {}

          ..$namespacedWrappers
        }

        object $namespacedTerm {
          def live[T : Tag : ResourceMetadata] =
            ZLayer.fromServices[SttpClient.Service, K8sCluster, $namespacedT[T]] {
              (backend: SttpClient.Service, cluster: K8sCluster) =>
                new $namespacedTT(
                  new SubresourceClient[$modelT](implicitly[ResourceMetadata[T]].resourceType, cluster, backend, $nameLit)
                )
            }
        }

        class $clusterT[T](asGenericSubresource: SubresourceClient[$modelT]) {
          {}
          ..$clusterWrappers
        }

        object $clusterTerm {
          def live[T : Tag : ResourceMetadata] =
            ZLayer.fromServices[SttpClient.Service, K8sCluster, $clusterT[T]] {
              (backend: SttpClient.Service, cluster: K8sCluster) =>
                new $clusterTT(
                  new SubresourceClient[$modelT](implicitly[ResourceMetadata[T]].resourceType, cluster, backend, $nameLit)
                )
            }
        }
        }
     """.toString
  }
}
