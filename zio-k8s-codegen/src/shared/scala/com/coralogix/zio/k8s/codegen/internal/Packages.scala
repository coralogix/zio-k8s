package com.coralogix.zio.k8s.codegen.internal

import io.github.vigoo.metagen.core.Package

object Packages {
  val zio: Package = Package("zio")

  val k8sModel: Package = Package("com", "coralogix", "zio", "k8s", "model")
  val k8sClientModel: Package = Package("com", "coralogix", "zio", "k8s", "client", "model")
  val k8sSubresources: Package = Package("com", "coralogix", "zio", "k8s", "client", "subresources")
}
