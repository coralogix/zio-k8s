package com.coralogix.zio.k8s.codegen

import sbt.Keys._
import sbt._
import scala.sys.process._
import scala.language.postfixOps

object K8sSwaggerPlugin extends AutoPlugin {
  object autoImport {

    lazy val k8sVersion = settingKey[String]("K8s version")
    lazy val getK8sSwagger = taskKey[File]("Downloads the K8s Swagger definition")
  }

  import autoImport._

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      k8sVersion    := "v1.22.1",
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
