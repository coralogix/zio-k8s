package com.coralogix.zio.k8s.crd.guardrail

import cats.Monad
import cats.data.NonEmptyList
import dev.guardrail.{ AuthImplementation, Target }
import dev.guardrail.core.{ SupportDefinition, Tracker }
import dev.guardrail.generators.{ CustomExtractionField, RenderedRoutes, TracingField }
import dev.guardrail.generators.scala.ScalaLanguage
import dev.guardrail.terms.protocol.StrictProtocolElems
import dev.guardrail.terms.server.{ GenerateRouteMeta, SecurityExposure, ServerTerms }
import dev.guardrail.terms.{ CollectionsLibTerms, Responses, SecurityScheme }
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
    securitySchemes: Map[String, SecurityScheme[ScalaLanguage]],
    securityExposure: SecurityExposure,
    authImplementation: AuthImplementation
  ): Target[RenderedRoutes[ScalaLanguage]] =
    Target.pure(
      RenderedRoutes(List.empty, List.empty, List.empty, List.empty, List.empty, List.empty)
    )

  override def getExtraRouteParams(
    resourceName: String,
    customExtraction: Boolean,
    tracing: Boolean,
    authImplementation: AuthImplementation,
    securityExposure: SecurityExposure
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
    securitySchemesDefinitions: List[Defn],
    customExtraction: Boolean,
    authImplementation: AuthImplementation
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
    supportPackage: NonEmptyList[String]
  ): Target[List[Import]] =
    Target.pure(List.empty)
}
