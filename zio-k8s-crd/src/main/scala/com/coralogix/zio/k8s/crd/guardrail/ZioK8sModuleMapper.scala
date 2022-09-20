package com.coralogix.zio.k8s.crd.guardrail

import scala.reflect.runtime.universe.typeTag

import dev.guardrail.Target
import dev.guardrail.generators.scala.ScalaLanguage
import dev.guardrail.generators.spi.ModuleMapperLoader

class ZioK8sModuleMapper extends ModuleMapperLoader {
  type L = ScalaLanguage
  def reified = typeTag[Target[ScalaLanguage]]
  def apply(frameworkName: String): Option[Set[String]] = frameworkName match {
    case "zio-k8s" => Some(Set("scala-language", "scala-stdlib", "circe", "http4s", "zio-k8s"))
    case _         => None
  }
}
