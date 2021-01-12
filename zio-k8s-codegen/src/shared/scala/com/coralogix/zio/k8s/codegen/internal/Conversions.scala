package com.coralogix.zio.k8s.codegen.internal

object Conversions {
  def groupNameToPackageName(groupName: String): Vector[String] =
    groupName
      .split('.')
      .filter(_.nonEmpty)
      .reverse
      .toVector

  def splitName(name: String): (Vector[String], String) = {
    val parts =
      if (name.startsWith("io.k8s.api"))
        name.split('.').drop(3)
      else
        name.split('.')
    val groupName = parts.init
      .filter(_.nonEmpty)
      .map(_.replace('-', '_'))
      .toVector
    (groupName, parts.last)
  }
}
