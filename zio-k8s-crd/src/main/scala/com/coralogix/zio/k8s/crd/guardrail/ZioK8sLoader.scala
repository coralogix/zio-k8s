package com.coralogix.zio.k8s.crd.guardrail

import scala.reflect.runtime.universe.typeTag

import dev.guardrail.Target
import dev.guardrail.generators.scala.ScalaLanguage
import dev.guardrail.generators.spi.FrameworkLoader

class ZioK8sLoader extends FrameworkLoader {
  type L = ScalaLanguage
  def reified = typeTag[Target[ScalaLanguage]]
}
