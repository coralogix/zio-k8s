package com.coralogix.zio.k8s.crd.guardrail

import cats.Monad
import cats.data.NonEmptyList
import dev.guardrail.Target
import dev.guardrail.core.SupportDefinition
import dev.guardrail.generators.{LanguageParameters, RenderedClientOperation}
import dev.guardrail.generators.scala.ScalaLanguage
import dev.guardrail.terms.{CollectionsLibTerms, Responses, RouteMeta, SecurityScheme}
import dev.guardrail.terms.client.ClientTerms
import dev.guardrail.terms.protocol.{StaticDefns, StrictProtocolElems}

import java.net.URI
import scala.meta.{Defn, Import, Term}

class SttpK8sClient(implicit cl: CollectionsLibTerms[ScalaLanguage, Target])
    extends ClientTerms[ScalaLanguage, Target] {
  override def MonadF: Monad[Target] = Target.targetInstances

  override def generateClientOperation(
    className: List[String],
    responseClsName: String,
    tracing: Boolean,
    securitySchemes: Map[String, SecurityScheme[ScalaLanguage]],
    parameters: LanguageParameters[ScalaLanguage]
  )(
    route: RouteMeta,
    methodName: String,
    responses: Responses[ScalaLanguage]
  ): Target[RenderedClientOperation[ScalaLanguage]] = ???

  override def getImports(tracing: Boolean): Target[List[Import]] = ???

  override def getExtraImports(tracing: Boolean): Target[List[Import]] = ???

  override def clientClsArgs(
    tracingName: Option[String],
    serverUrls: Option[NonEmptyList[URI]],
    tracing: Boolean
  ): Target[List[List[Term.Param]]] = ???

  override def generateResponseDefinitions(
    responseClsName: String,
    responses: Responses[ScalaLanguage],
    protocolElems: List[StrictProtocolElems[ScalaLanguage]]
  ): Target[List[Defn]] = ???

  override def generateSupportDefinitions(
    tracing: Boolean,
    securitySchemes: Map[String, SecurityScheme[ScalaLanguage]]
  ): Target[List[SupportDefinition[ScalaLanguage]]] = ???

  override def buildStaticDefns(
    clientName: String,
    tracingName: Option[String],
    serverUrls: Option[NonEmptyList[URI]],
    ctorArgs: List[List[Term.Param]],
    tracing: Boolean
  ): Target[StaticDefns[ScalaLanguage]] = ???

  override def buildClient(
    clientName: String,
    tracingName: Option[String],
    serverUrls: Option[NonEmptyList[URI]],
    basePath: Option[String],
    ctorArgs: List[List[Term.Param]],
    clientCalls: List[Defn],
    supportDefinitions: List[Defn],
    tracing: Boolean
  ): Target[NonEmptyList[Either[Defn.Trait, Defn.Class]]] = ???
}
