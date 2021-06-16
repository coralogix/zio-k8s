package com.coralogix.zio.k8s.crd.guardrail

import cats.Monad
import cats.data.{ NonEmptyList, NonEmptyVector }
import cats.implicits._
import com.twilio.guardrail.{
  DataVisible,
  EmptyIsNull,
  ProtocolParameter,
  StaticDefns,
  SuperClass,
  SwaggerUtil,
  Target,
  UserError
}
import com.twilio.guardrail.core.Tracker
import com.twilio.guardrail.generators.{ RawParameterName, ScalaGenerator }
import com.twilio.guardrail.generators.Scala.CirceProtocolGenerator
import com.twilio.guardrail.generators.Scala.CirceProtocolGenerator.suffixClsName
import com.twilio.guardrail.generators.Scala.model.CirceModelGenerator
import com.twilio.guardrail.languages.ScalaLanguage
import com.twilio.guardrail.protocol.terms.protocol.{
  ModelProtocolTerms,
  PropMeta,
  PropertyRequirement
}
import com.twilio.guardrail.terms.CollectionsLibTerms
import io.swagger.v3.oas.models.media.Schema

import scala.meta._

class K8sModelProtocolTermInterp(implicit
  collectionsLibTerms: CollectionsLibTerms[ScalaLanguage, Target],
  k8sContext: K8sCodegenContext
) extends ModelProtocolTerms[ScalaLanguage, Target] {

  private val circeVersion = CirceModelGenerator.V012
  private val circe = new CirceProtocolGenerator.ModelProtocolTermInterp(circeVersion)

  private val kindLit = Lit.String(k8sContext.kind)
  private val apiVersionLit = Lit.String(s"${k8sContext.group}/${k8sContext.version}")
  private val pluralLit = Lit.String(k8sContext.plural)
  private val groupLit = Lit.String(k8sContext.group)
  private val versionLit = Lit.String(k8sContext.version)

  private val reservedNames = Set("wait", "equals", "toString", "notify", "notifyAll", "finalize")

  override def MonadF: Monad[Target] = Target.targetInstances

  override def extractProperties(
    swagger: Tracker[Schema[_]]
  ): Target[List[(String, Tracker[Schema[_]])]] =
    circe.extractProperties(swagger)

  override def transformProperty(
    clsName: String,
    dtoPackage: List[String],
    supportPackage: List[String],
    concreteTypes: List[PropMeta[ScalaLanguage]]
  )(
    name: String,
    fieldName: String,
    prop: Tracker[Schema[_]],
    meta: SwaggerUtil.ResolvedType[ScalaLanguage],
    requirement: PropertyRequirement,
    isCustomType: Boolean,
    defaultValue: Option[Term]
  ): Target[ProtocolParameter[ScalaLanguage]] =
    circe.transformProperty(clsName, dtoPackage, supportPackage, concreteTypes)(
      name,
      if (reservedNames.contains(fieldName)) fieldName + "_" else fieldName,
      prop,
      meta,
      requirement,
      isCustomType,
      defaultValue
    )

  override def renderDTOClass(
    clsName: String,
    supportPackage: List[String],
    selfParams: List[ProtocolParameter[ScalaLanguage]],
    parents: List[SuperClass[ScalaLanguage]]
  ): Target[Defn.Class] = {
    // NOTE: Customized version of CirceProtocolGenerator.ModelProtocolTermInterp
    val discriminators = parents.flatMap(_.discriminators)
    val discriminatorNames = discriminators.map(_.propertyName).toSet
    val parentOpt =
      if (parents.exists(s => s.discriminators.nonEmpty))
        parents.headOption
      else
        None
    val params = (
      parents.reverse.flatMap(_.params) ++
        selfParams
    ).filterNot(param => discriminatorNames.contains(param.term.name.value))

    val terms = params.map(_.term)

    val toStringMethod = if (params.exists(_.dataRedaction != DataVisible)) {
      def mkToStringTerm(param: ProtocolParameter[ScalaLanguage]): Term =
        param match {
          case param if param.dataRedaction == DataVisible =>
            q"${Term.Name(param.term.name.value)}.toString()"
          case _                                           => Lit.String("[redacted]")
        }

      val toStringTerms =
        params.map(p => List(mkToStringTerm(p))).intercalate(List(Lit.String(",")))

      List[Defn.Def](
        q"override def toString: String = ${toStringTerms
          .foldLeft[Term](Lit.String(s"${clsName}("))((accum, term) => q"$accum + $term")} + ${Lit.String(")")}"
      )
    } else
      List.empty[Defn.Def]

    // format: off
    val withoutParent =
        q"""case class ${Type.Name(clsName)}(..${terms}) { ..$toStringMethod }"""

    val code = parentOpt
      .fold(withoutParent) { parent =>
          q"""case class ${Type.Name(clsName)}(..${terms}) extends ${template"..${init"${Type.Name(parent.clsName)}(...$Nil)" ::
            parent.interfaces.map(a => init"${Type.Name(a)}(...$Nil)")} { ..$toStringMethod }"}"""
      }

    // format: on
    Target.pure(code)
  }

  override def decodeModel(
    clsName: String,
    dtoPackage: List[String],
    supportPackage: List[String],
    selfParams: List[ProtocolParameter[ScalaLanguage]],
    parents: List[SuperClass[ScalaLanguage]] = Nil
  ) = {
    // NOTE: Customized version of CirceProtocolGenerator.ModelProtocolTermInterp

    val discriminators = parents.flatMap(_.discriminators)
    val discriminatorNames = discriminators.map(_.propertyName).toSet
    val params = (parents.reverse.flatMap(_.params) ++ selfParams).filterNot(param =>
      discriminatorNames.contains(param.name.value)
    )
    val needsEmptyToNull: Boolean = params.exists(_.emptyToNull == EmptyIsNull)
    val paramCount = params.length
    for {
      presence <-
        ScalaGenerator.ScalaInterp.selectTerm(NonEmptyList.ofInitLast(supportPackage, "Presence"))
      decVal   <- if (paramCount == 0)
                    Target.pure(Option.empty[Term])
                  else if (paramCount <= 22 && !needsEmptyToNull) {
                    val names: List[Lit] = params.map(_.name.value).map(Lit.String(_)).to[List]
                    Target.pure(
                      Option(
                        q"""
                    Decoder.${Term.Name(s"forProduct${paramCount}")}(..${names})(${Term
                          .Name(clsName)}.apply _)
                  """
                      )
                    )
                  } else
                    params.zipWithIndex
                      .traverse({ case (param, idx) =>
                        for {
                          rawTpe <- Target.fromOption(param.term.decltpe, UserError("Missing type"))
                          tpe    <- rawTpe match {
                                      case tpe: Type => Target.pure(tpe)
                                      case x         =>
                                        Target.raiseUserError(
                                          s"Unsure how to map ${x.structure}, please report this bug!"
                                        )
                                    }
                        } yield {
                          val term = Term.Name(s"v$idx")
                          val name = Lit.String(param.name.value)

                          val emptyToNull: Term => Term = if (param.emptyToNull == EmptyIsNull) { t =>
                            q"$t.withFocus(j => j.asString.fold(j)(s => if(s.isEmpty) Json.Null else j))"
                          } else identity _

                          val decodeField: Type => NonEmptyVector[Term => Term] = { tpe =>
                            NonEmptyVector.of[Term => Term](
                              t => q"$t.downField($name)",
                              emptyToNull,
                              t => q"$t.as[${tpe}]"
                            )
                          }

                          val decodeOptionalField
                            : Type => (Term => Term, Term) => NonEmptyVector[Term => Term] = {
                            tpe => (present, absent) =>
                              NonEmptyVector.of[Term => Term](t =>
                                q"""
                          ((c: HCursor) =>
                            c
                              .value
                              .asObject
                              .filter(!_.contains($name))
                              .fold(${emptyToNull(
                                  q"c.downField($name)"
                                )}.as[${tpe}].map(x => ${present(q"x")})) { _ =>
                                Right($absent)
                              }
                          )($t)
                        """
                              )
                          }

                          def decodeOptionalRequirement(
                            param: ProtocolParameter[ScalaLanguage]
                          ): PropertyRequirement.OptionalRequirement => NonEmptyVector[
                            Term => Term
                          ] = {
                            case PropertyRequirement.OptionalLegacy   =>
                              decodeField(tpe)
                            case PropertyRequirement.RequiredNullable =>
                              decodeField(t"Json") :+ (t => q"$t.flatMap(_.as[${tpe}])")
                            case PropertyRequirement.Optional         => // matched only where there is inconsistency between encoder and decoder
                              decodeOptionalField(param.baseType)(x => q"Option($x)", q"None")
                          }

                          val parseTermAccessors: NonEmptyVector[Term => Term] =
                            param.propertyRequirement match {
                              case PropertyRequirement.Required                          =>
                                decodeField(tpe)
                              case PropertyRequirement.OptionalNullable                  =>
                                decodeOptionalField(t"Option[${param.baseType}]")(
                                  x => q"$presence.present($x)",
                                  q"$presence.absent"
                                )
                              case PropertyRequirement.Optional | PropertyRequirement.Configured(
                                    PropertyRequirement.Optional,
                                    PropertyRequirement.Optional
                                  ) =>
                                decodeOptionalField(param.baseType)(
                                  x => q"$presence.present($x)",
                                  q"$presence.absent"
                                )
                              case requirement: PropertyRequirement.OptionalRequirement  =>
                                decodeOptionalRequirement(param)(requirement)
                              case PropertyRequirement.Configured(_, decoderRequirement) =>
                                decodeOptionalRequirement(param)(decoderRequirement)
                            }

                          val parseTerm =
                            parseTermAccessors.foldLeft[Term](q"c")((acc, next) => next(acc))
                          val enum = enumerator"""${Pat.Var(term)} <- $parseTerm"""
                          (term, enum)
                        }
                      })(MonadF)
                      .map({ pairs =>
                        val (terms, enumerators) = pairs.unzip
                        Option(
                          q"""
                    new Decoder[${Type.Name(clsName)}] {
                      final def apply(c: HCursor): Decoder.Result[${Type.Name(clsName)}] =
                        for {
                          ..${enumerators}
                        } yield ${Term.Name(clsName)}(..${terms})
                    }
                  """
                        )
                      })
    } yield decVal.map(decVal =>
      q"""
              implicit val ${suffixClsName("decode", clsName)}: Decoder[${Type
        .Name(clsName)}] = $decVal
            """
    )
  }

  override def encodeModel(
    clsName: String,
    dtoPackage: List[String],
    selfParams: List[ProtocolParameter[ScalaLanguage]],
    parents: List[SuperClass[ScalaLanguage]]
  ): Target[Option[Defn.Val]] = {
    // NOTE: Customized version of CirceProtocolGenerator.ModelProtocolTermInterp

    val discriminators = parents.flatMap(_.discriminators)
    val discriminatorNames = discriminators.map(_.propertyName).toSet
    val params = (parents.reverse.flatMap(_.params) ++ selfParams).filterNot(param =>
      discriminatorNames.contains(param.name.value)
    )
    val readOnlyKeys: List[String] = params.flatMap(_.readOnlyKey).toList
    val paramCount = params.length
    val typeName = Type.Name(clsName)
    val encVal =
      if (paramCount == 0)
        Option.empty[Term]
      else {
        def encodeRequired(param: ProtocolParameter[ScalaLanguage]) =
          q"""(${Lit.String(param.name.value)}, a.${Term.Name(param.term.name.value)}.asJson)"""

        def encodeOptional(param: ProtocolParameter[ScalaLanguage]) = {
          val name = Lit.String(param.name.value)
          q"a.${Term.Name(param.term.name.value)}.fold(ifAbsent = None, ifPresent = value => Some($name -> value.asJson))"
        }

        val allFields: List[Either[Term.Apply, Term.Tuple]] = params.map { param =>
          val name = Lit.String(param.name.value)
          param.propertyRequirement match {
            case PropertyRequirement.Required | PropertyRequirement.RequiredNullable |
                PropertyRequirement.OptionalLegacy =>
              Right(encodeRequired(param))
            case PropertyRequirement.Optional | PropertyRequirement.OptionalNullable =>
              Left(encodeOptional(param))
            case PropertyRequirement.Configured(
                  PropertyRequirement.Optional,
                  PropertyRequirement.Optional
                ) =>
              Left(encodeOptional(param))
            case PropertyRequirement.Configured(
                  PropertyRequirement.RequiredNullable | PropertyRequirement.OptionalLegacy,
                  _
                ) =>
              Right(encodeRequired(param))
            case PropertyRequirement.Configured(PropertyRequirement.Optional, _)     =>
              Left(q"""a.${Term.Name(param.term.name.value)}.map(value => (${Lit.String(
                param.name.value
              )}, value.asJson))""")
          }
        }

        val pairs: List[Term.Tuple] = allFields.collect { case Right(pair) =>
          pair
        }
        val optional = allFields.collect { case Left(field) =>
          field
        }
        val simpleCase = q"Vector(..${pairs})"
        val arg = optional.foldLeft[Term](simpleCase) { (acc, field) =>
          q"$acc ++ $field"
        }
        Option(
          q"""
                ${circeVersion.encoderObjectCompanion}.instance[${Type
            .Name(clsName)}](a => JsonObject.fromIterable($arg))
              """
        )
      }

    val containsObjectMetadataField = params.exists(param => param.name.value == "metadata")
    val isTopLevel = clsName.toLowerCase == k8sContext.kind.toLowerCase

    val encoder = encVal.map(e => q"""$e
                        .mapJsonObject(
                          _.filterKeys(key => !(readOnlyKeys contains key)))
                        .mapJsonObject(_.filter { case (_, v) => !v.isNull })""")

    if (containsObjectMetadataField && isTopLevel)
      Target.pure(encoder.map(encoder => q"""
            implicit val ${suffixClsName("encode", clsName)}: ${circeVersion.encoderObject}[${Type.Name(clsName)}] = {
              val readOnlyKeys = Set[String](..${readOnlyKeys.map(Lit.String(_))})
              $encoder
                .mapJsonObject(
                  ("kind", $kindLit.asJson) +:
                  ("apiVersion", $apiVersionLit.asJson) +: _
                )
            }
          """))
    else
      Target.pure(encoder.map(encoder => q"""
            implicit val ${suffixClsName("encode", clsName)}: ${circeVersion.encoderObject}[${Type.Name(clsName)}] = {
              val readOnlyKeys = Set[String](..${readOnlyKeys.map(Lit.String(_))})
              $encoder
            }
          """))
  }

  override def renderDTOStaticDefns(
    clsName: String,
    deps: List[Term.Name],
    encoder: Option[Defn.Val],
    decoder: Option[Defn.Val]
  ): Target[StaticDefns[ScalaLanguage]] = {
    val entityNameT = Type.Name(clsName)
    val entityNameTerm = Term.Name(clsName)

    val k8sObject: Defn =
      if (k8sContext.isMetadataOptional) {
        q"""implicit val k8sObject: K8sObject[$entityNameT] =
                  new K8sObject[$entityNameT] {
                    def metadata(obj: $entityNameT): Optional[ObjectMeta] =
                      obj.metadata
                    def mapMetadata(f: ObjectMeta => ObjectMeta)(obj: $entityNameT): $entityNameT =
                      obj.copy(metadata = obj.metadata.map(f))
                  }
            """
      } else {
        q"""implicit val k8sObject: K8sObject[$entityNameT] =
                  new K8sObject[$entityNameT] {
                    def metadata(obj: $entityNameT): Optional[ObjectMeta] =
                      Optional.Present(obj.metadata)
                    def mapMetadata(f: ObjectMeta => ObjectMeta)(obj: $entityNameT): $entityNameT =
                      obj.copy(metadata = f(obj.metadata))
                  }
            """
      }

    val ops: Defn =
      q"""implicit class Ops(protected val obj: $entityNameT)
                  extends K8sObjectOps[$entityNameT] {
                  protected override val impl: K8sObject[$entityNameT] = k8sObject
                }
             """

    val status =
      if (k8sContext.hasStatus) {
        val statusT = Type.Select(entityNameTerm, Type.Name("Status"))
        val k8sObjectStatus: Defn =
          q"""implicit val k8sObjectStatus: com.coralogix.zio.k8s.client.model.K8sObjectStatus[$entityNameT, $statusT] =
                new com.coralogix.zio.k8s.client.model.K8sObjectStatus[$entityNameT, $statusT] {
                  def status(obj: $entityNameT): Optional[$statusT] =
                    obj.status
                  def mapStatus(f: $statusT => $statusT)(obj: $entityNameT): $entityNameT =
                    obj.copy(status = obj.status.map(f))
                }
            """

        val statusOps: Defn =
          q"""implicit class StatusOps(protected val obj: $entityNameT)
                extends com.coralogix.zio.k8s.client.model.K8sObjectStatusOps[$entityNameT, $statusT] {
                protected override val impl: com.coralogix.zio.k8s.client.model.K8sObjectStatus[$entityNameT, $statusT] = k8sObjectStatus
              }
             """

        List(k8sObjectStatus, statusOps)
      } else {
        Nil
      }

    val metadata: Defn =
      q"""implicit val metadata: ResourceMetadata[$entityNameT] =
                                new ResourceMetadata[$entityNameT] {
                                  override val kind: String = $kindLit
                                  override val apiVersion: String = $apiVersionLit
                                  override val resourceType: K8sResourceType = K8sResourceType($pluralLit, $groupLit, $versionLit)
                                }
                           """

    val isTopLevel = clsName.toLowerCase == k8sContext.kind.toLowerCase

    circe
      .renderDTOStaticDefns(clsName, deps, encoder, decoder)
      .map(sdefs =>
        sdefs.copy(
          extraImports =
            q"import com.coralogix.zio.k8s.client.model.{K8sObject, K8sObjectOps, K8sResourceType, Optional, ResourceMetadata}" ::
              q"import com.coralogix.zio.k8s.client.model.primitives._" ::
              sdefs.extraImports,
          definitions = if (isTopLevel) {
            k8sObject :: ops :: metadata :: status ::: sdefs.definitions
          } else sdefs.definitions
        )
      )
  }
}
