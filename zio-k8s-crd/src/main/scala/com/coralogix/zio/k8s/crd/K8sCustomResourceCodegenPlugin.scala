package com.coralogix.zio.k8s.crd

import sbt._
import sbt.Keys._
import zio.nio.file.{ Path => ZPath }
import zio.Unsafe

object K8sCustomResourceCodegenPlugin extends AutoPlugin {

  object autoImport {
    val externalCustomResourceDefinitions =
      settingKey[Seq[File]]("List of external K8s CRDs to generate clients and models for")

    // TODO: cache by source yaml hash

    lazy val generateSources =
      Def.task {
        val log = streams.value.log
        val runtime = zio.Runtime.default
        val scalaVer = scalaVersion.value

        val crds = externalCustomResourceDefinitions.value
        val sourcesDir = (Compile / sourceManaged).value

        val cachedFun = FileFunction.cached(
          streams.value.cacheDirectory / "k8s-crd-src"
        ) { input: Set[File] =>
          Unsafe.unsafe { implicit u =>
            input.foldLeft(Set.empty[File]) { (result, crdYaml) =>
              val fs = runtime.unsafe
                .run {
                  val codegen = new K8sCustomResourceCodegen(scalaVer)
                  codegen.generateSource(
                    ZPath.fromJava(crdYaml.toPath),
                    ZPath.fromJava(sourcesDir.toPath),
                    log
                  )
                }
                .getOrThrowFiberFailure()
              result union fs.toSet
            }
          }
        }

        cachedFun(crds.toSet).toSeq
      }

    lazy val copyResourceDefinitions =
      Def.task {

        val s = streams.value
        val log = s.log
        val runtime = zio.Runtime.default

        val crds = externalCustomResourceDefinitions.value
        val resourcesDir = (Compile / resourceManaged).value
        val scalaVer = scalaVersion.value

        val cachedFun = FileFunction.cached(
          streams.value.cacheDirectory / "k8s-crd-res"
        ) { input: Set[File] =>
          Unsafe.unsafe { implicit u =>
            input.foldLeft(Set.empty[File]) { (result, crdYaml) =>
              val fs = runtime.unsafe
                .run(
                  new K8sCustomResourceCodegen(scalaVer).generateResource(
                    ZPath.fromJava(crdYaml.toPath),
                    ZPath.fromJava(resourcesDir.toPath),
                    log
                  )
                )
                .getOrThrowFiberFailure()
              result union fs.toSet
            }
          }
        }

        cachedFun(crds.toSet).toSeq
      }
  }

  import autoImport._

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      Compile / sourceGenerators += generateSources.taskValue,
      Compile / resourceGenerators += copyResourceDefinitions.taskValue,
      Compile / packageSrc / mappings ++= {
        val base = (Compile / sourceManaged).value
        val files = (Compile / managedSources).value
        files.map(f => (f, f.relativeTo(base).get.getPath))
      },
      externalCustomResourceDefinitions := Seq.empty
    )
}
