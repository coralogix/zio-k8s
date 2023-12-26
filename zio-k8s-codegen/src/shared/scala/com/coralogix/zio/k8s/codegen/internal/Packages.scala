package com.coralogix.zio.k8s.codegen.internal

import io.github.vigoo.metagen.core.Package

object Packages {
  val zio: Package = Package("zio")
  val zioOptics: Package = zio / "optics"
  val zioPrelude: Package = zio / "prelude"

  val k8sModel: Package = Package("com", "coralogix", "zio", "k8s", "model")
  val k8sClient: Package = Package("com", "coralogix", "zio", "k8s", "client")
  val k8sClientImpl: Package = k8sClient / "impl"
  val k8sClientTest: Package = k8sClient / "test"
  val k8sClientModel: Package = k8sClient / "model"
  val k8sSubresources: Package = k8sClient / "subresources"

  val circe: Package = Package("io", "circe")
  val monocle: Package = Package("monocle")
  val sttp: Package = Package("sttp")
}