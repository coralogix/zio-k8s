package com.coralogix.zio.k8s.codegen

import sbt.Keys._
import sbt._
import zio.nio.core.file.{ Path => ZPath }

object K8sResourceCodegenPlugin extends AutoPlugin {
  object autoImport {
    lazy val generateSources =
      Def.task {
        val log = streams.value.log
        val runtime = zio.Runtime.default

        val sourcesDir = (Compile / sourceManaged).value

        // TODO: make it cached depending on the swagger

        val fs = runtime.unsafeRun(
          K8sResourceCodegen.generateAll(
            ZPath.fromJava(sourcesDir.toPath),
            log
          )
        )

        fs
      }
  }

  import autoImport._

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      Compile / sourceGenerators += generateSources.taskValue,
      mappings in (Compile, packageSrc) ++= {
        val base = (sourceManaged in Compile).value
        val files = (managedSources in Compile).value
        files.map(f => (f, f.relativeTo(base).get.getPath))
      }
    )
}
