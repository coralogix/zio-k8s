package com.coralogix.zio.k8s.crd.guardrail

import cats.Monad
import com.twilio.guardrail.{
  CustomExtractionField,
  RenderedRoutes,
  StrictProtocolElems,
  SupportDefinition,
  Target,
  TracingField
}
import com.twilio.guardrail.core.Tracker
import com.twilio.guardrail.languages.ScalaLanguage
import com.twilio.guardrail.protocol.terms.Responses
import com.twilio.guardrail.protocol.terms.server.{ GenerateRouteMeta, ServerTerms }
import com.twilio.guardrail.terms.{ CollectionsLibTerms, SecurityScheme }
import io.swagger.v3.oas.models.Operation

import scala.meta._

class NoServerSupport(implicit cl: CollectionsLibTerms[ScalaLanguage, Target])
    extends ServerTerms[ScalaLanguage, Target] {
  override def MonadF: Monad[Target] = Target.targetInstances

  override def buildCustomExtractionFields(
    operation: Tracker[Operation],
    resourceName: List[String],
    customExtraction: Boolean
  ): Target[Option[CustomExtractionField[ScalaLanguage]]] =
    Target.pure(None)

  override def buildTracingFields(
    operation: Tracker[Operation],
    resourceName: List[String],
    tracing: Boolean
  ): Target[Option[TracingField[ScalaLanguage]]] =
    Target.pure(None)

  override def generateRoutes(
    tracing: Boolean,
    resourceName: String,
    handlerName: String,
    basePath: Option[String],
    routes: List[GenerateRouteMeta[ScalaLanguage]],
    protocolElems: List[StrictProtocolElems[ScalaLanguage]],
    securitySchemes: Map[String, SecurityScheme[ScalaLanguage]]
  ): Target[RenderedRoutes[ScalaLanguage]] =
    Target.pure(RenderedRoutes(List.empty, List.empty, List.empty, List.empty, List.empty))

  override def getExtraRouteParams(
    customExtraction: Boolean,
    tracing: Boolean
  ): Target[List[Term.Param]] =
    Target.pure(List.empty)

  override def generateResponseDefinitions(
    responseClsName: String,
    responses: Responses[ScalaLanguage],
    protocolElems: List[StrictProtocolElems[ScalaLanguage]]
  ): Target[List[Defn]] =
    Target.pure(List.empty)

  override def generateSupportDefinitions(
    tracing: Boolean,
    securitySchemes: Map[String, SecurityScheme[ScalaLanguage]]
  ): Target[List[SupportDefinition[ScalaLanguage]]] =
    Target.pure(List.empty)

  override def renderClass(
    resourceName: String,
    handlerName: String,
    annotations: List[Mod.Annot],
    combinedRouteTerms: List[Stat],
    extraRouteParams: List[Term.Param],
    responseDefinitions: List[Defn],
    supportDefinitions: List[Defn],
    customExtraction: Boolean
  ): Target[List[Defn]] =
    Target.pure(List.empty)

  override def renderHandler(
    handlerName: String,
    methodSigs: List[Decl.Def],
    handlerDefinitions: List[Stat],
    responseDefinitions: List[Defn],
    customExtraction: Boolean
  ): Target[Defn] =
    Target.pure(
      q"""
          trait ${Type.Name(handlerName)} {}
        """
    )

  override def getExtraImports(
    tracing: Boolean,
    supportPackage: List[String]
  ): Target[List[Import]] =
    Target.pure(List.empty)
}
