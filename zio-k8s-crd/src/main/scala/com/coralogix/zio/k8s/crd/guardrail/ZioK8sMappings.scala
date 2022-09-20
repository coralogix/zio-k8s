package com.coralogix.zio.k8s.crd.guardrail

import cats.syntax.all.*

import _root_.scala.meta.*
import dev.guardrail.core.CoreTermInterp
import dev.guardrail.generators.scala.ScalaLanguage
import dev.guardrail.generators.spi.{ FrameworkLoader, ModuleMapperLoader }
import dev.guardrail.{ MissingDependency, UnparseableArgument }

import scala.meta.{ Import, Importer }

object ZioK8sMappings {
  implicit def interpreter = new CoreTermInterp[ScalaLanguage](
    "zio-k8s",
    FrameworkLoader.load[ScalaLanguage](_),
    frameworkName => ModuleMapperLoader.load[ScalaLanguage](frameworkName),
    _.parse[Importer].toEither
      .bimap(err => UnparseableArgument("import", err.toString), importer => Import(List(importer)))
  )
}
