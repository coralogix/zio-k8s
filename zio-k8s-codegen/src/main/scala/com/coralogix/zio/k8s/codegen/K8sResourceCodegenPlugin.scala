package com.coralogix.zio.k8s.codegen

import sbt.Keys._
import sbt._
import scala.sys.process._
import zio.nio.core.file.{ Path => ZPath }

object K8sResourceCodegenPlugin extends AutoPlugin {
  object autoImport {

    lazy val k8sVersion = settingKey[String]("K8s version")
    lazy val getK8sSwagger = taskKey[File]("Downloads the K8s Swagger definition")

    lazy val generateSources =
      Def.task {
        val log = streams.value.log
        val runtime = zio.Runtime.default

        val sourcesDir = (Compile / sourceManaged).value
        val ver = scalaVersion.value

        val cachedFun = FileFunction.cached(
          streams.value.cacheDirectory / s"k8s-src-${ver}",
          FileInfo.hash
        ) { input: Set[File] =>
          input.foldLeft(Set.empty[File]) { (result, k8sSwagger) =>
            val fs = runtime.unsafeRun(
              K8sResourceCodegen.generateAll(
                log,
                ZPath.fromJava(k8sSwagger.toPath),
                ZPath.fromJava(sourcesDir.toPath)
              )
            )
            result union fs.toSet
          }
        }

        val k8sSwagger = getK8sSwagger.value

        cachedFun(Set(k8sSwagger)).toSeq
      }
  }

  import autoImport._

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      Compile / sourceGenerators += generateSources.taskValue,
      k8sVersion    := "v1.20.1",
      getK8sSwagger := getK8sSwaggerTask.value
    )

  private lazy val getK8sSwaggerTask =
    Def.task {
      val ver = k8sVersion.value
      val targetDir = target.value / "k8s-swagger.json"
      val source =
        url(
          s"https://raw.githubusercontent.com/kubernetes/kubernetes/$ver/api/openapi-spec/swagger.json"
        )
      source #> targetDir !

      targetDir
    }
}
