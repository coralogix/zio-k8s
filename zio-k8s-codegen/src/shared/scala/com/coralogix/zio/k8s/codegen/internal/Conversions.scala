package com.coralogix.zio.k8s.codegen.internal

import io.github.vigoo.metagen.core._

object Conversions {
  val modelRoot: Package = Packages.k8sModel

  def groupNameToPackageName(groupName: String): Vector[String] = {
    val base = groupName
      .split('.')
      .filter(_.nonEmpty)
      .reverse
      .toVector

    if (base.length > 2 && base(0) == "io" && base(1) == "k8s")
      base.drop(2)
    else base
  }

  def splitName(name: String, root: Package = modelRoot): ScalaType = {
    val parts =
      if (name.startsWith("io.k8s.api"))
        name.split('.').drop(3)
      else
        name.split('.')
    val groupName = parts.init
      .filter(_.nonEmpty)
      .map(_.replace('-', '_'))
      .toVector

    ScalaType(groupName.foldLeft(root)(_ / _), parts.last)
  }
}
