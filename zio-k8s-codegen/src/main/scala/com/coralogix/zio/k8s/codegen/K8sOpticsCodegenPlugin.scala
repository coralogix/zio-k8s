package com.coralogix.zio.k8s.codegen

import com.coralogix.zio.k8s.codegen.K8sSwaggerPlugin.autoImport.*
import io.github.vigoo.metagen.core.*
import sbt.Keys.*
import sbt.*
import zio.Unsafe
import zio.nio.file.Path as ZPath

object K8sOpticsCodegenPlugin extends AutoPlugin {
  object autoImport {
    lazy val generateSources =
      Def.task {
        val log = streams.value.log
        val runtime = zio.Runtime.default
        val scalaVer = scalaVersion.value
        val codegen = new K8sResourceCodegen(log, scalaVer)

        val sourcesDir = (Compile / sourceManaged).value
        val ver = scalaVersion.value

        val cachedFun = FileFunction.cached(
          streams.value.cacheDirectory / s"k8s-optics-src-$ver",
          FileInfo.hash
        ) { input: Set[File] =>
          input.foldLeft(Set.empty[File]) { (result, k8sSwagger) =>
            Unsafe.unsafe { implicit u =>
              val fs = runtime.unsafe.run {
                val generator =
                  for {
                    _ <- Generator.setRoot(ZPath.fromJava(sourcesDir.toPath))
                    _ <- Generator.setScalaVersion(scalaVer)
                    _ <- Generator.enableFormatting()
                    result <- codegen.generateAllOptics(
                      ZPath.fromJava(k8sSwagger.toPath)
                    )
                  } yield result

                generator.provideLayer(Generator.live)
              }.getOrThrowFiberFailure()
              result union fs.toSet
            }
          }
        }

        val k8sSwagger = getK8sSwagger.value

        cachedFun(Set(k8sSwagger)).toSeq
      }
  }

  import autoImport._

  override val requires = K8sSwaggerPlugin

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      Compile / sourceGenerators += generateSources.taskValue
    )
}
