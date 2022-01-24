package com.coralogix.zio.k8s.codegen.internal

import io.github.vigoo.metagen.core._

object Types {
  def zio(r: ScalaType, e: ScalaType, a: ScalaType): ScalaType =
    ScalaType(Packages.zio, "ZIO", r, e, a)

  def chunk(a: ScalaType): ScalaType =
    ScalaType(Packages.zio, "Chunk", a)

  val status: ScalaType = ScalaType(
    Package("com", "coralogix", "zio", "k8s", "model", "pkg", "apis", "meta", "v1"),
    "Status"
  )

  def optional(a: ScalaType): ScalaType =
    ScalaType(Packages.k8sClientModel, "Optional", a)
}
