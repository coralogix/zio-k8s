package com.coralogix.zio.k8s.crd.guardrail

import dev.guardrail.Target
import dev.guardrail.generators.{ Framework, SwaggerGenerator }
import dev.guardrail.generators.scala.{
  CirceModelGenerator,
  ScalaCollectionsGenerator,
  ScalaGenerator,
  ScalaLanguage
}
import dev.guardrail.generators.scala.circe.CirceProtocolGenerator
import dev.guardrail.generators.scala.http4s.Http4sGenerator
import dev.guardrail.terms.client.ClientTerms
import dev.guardrail.terms.{ CollectionsLibTerms, LanguageTerms, ProtocolTerms, SwaggerTerms }
import dev.guardrail.terms.framework.FrameworkTerms
import dev.guardrail.terms.server.ServerTerms

// Custom code generator framework

// if we will start generating models from the k8s OpenAPI specs we want to customize
// the generated models (naming, split to package)
// if we want to generate clients too then SttpK8sClient has to be implemented
class ZioK8s(implicit k8sContext: K8sCodegenContext) extends Framework[ScalaLanguage, Target] {

  override implicit def ClientInterp: ClientTerms[ScalaLanguage, Target] =
    new SttpK8sClient

  override implicit def FrameworkInterp: FrameworkTerms[ScalaLanguage, Target] =
    Http4sGenerator()

  override implicit def ProtocolInterp: ProtocolTerms[ScalaLanguage, Target] =
    CirceProtocolGenerator(CirceModelGenerator.V012)

  override implicit def ServerInterp: ServerTerms[ScalaLanguage, Target] =
    new NoServerSupport

  override implicit def SwaggerInterp: SwaggerTerms[ScalaLanguage, Target] =
    SwaggerGenerator[ScalaLanguage]

  override implicit def LanguageInterp: LanguageTerms[ScalaLanguage, Target] =
    ScalaGenerator()

  override implicit def CollectionsLibInterp: CollectionsLibTerms[ScalaLanguage, Target] =
    ScalaCollectionsGenerator()
}
