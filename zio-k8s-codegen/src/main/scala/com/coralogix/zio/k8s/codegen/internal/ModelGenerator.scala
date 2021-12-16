package com.coralogix.zio.k8s.codegen.internal

import io.github.vigoo.metagen.core._
import io.swagger.v3.oas.models.media.{ArraySchema, ObjectSchema, Schema}
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.ZIO
import zio.blocking.Blocking
import com.coralogix.zio.k8s.codegen.internal.CodegenIO.writeTextFile
import com.coralogix.zio.k8s.codegen.internal.Conversions.{modelRoot, splitName}
import zio.nio.file.Path
import zio.nio.file.Files

import scala.collection.JavaConverters._
import scala.meta._

trait ModelGenerator {
  this: Common =>

  def logger: sbt.Logger

  protected def generateAllModels(
    scalafmt: Scalafmt,
    targetRoot: Path,
    definitionMap: Map[String, IdentifiedSchema],
    resources: Set[SupportedResource]
  ): ZIO[Blocking, Throwable, Set[Path]] = {
    val filteredDefinitions = definitionMap.values.filter(d => !isListModel(d)).toSet
    for {
      _     <- ZIO.effect(logger.info(s"Generating code for ${filteredDefinitions.size} models..."))
      paths <- ZIO.foreach(filteredDefinitions) { d =>
                 val entity = splitName(d.name)

                 for {
                   _         <- ZIO.effect(logger.info(s"Generating '${entity.name}' to ${entity.pkg.show}"))
                   src        = generateModel(modelRoot, entity, d, resources, definitionMap)
                   targetDir  = targetRoot / entity.pkg.asPath
                   _         <- Files.createDirectories(targetDir)
                   targetPath = targetDir / s"${entity.name}.scala"
                   _         <- writeTextFile(targetPath, src)
                   _         <- format(scalafmt, targetPath)
                 } yield targetPath
               }
    } yield paths
  }

  protected def isListModel(model: IdentifiedSchema): Boolean =
    model.name.endsWith("List") // NOTE: better check: has 'metadata' field of type 'ListMeta'

  private def findPluralName(
    gvk: GroupVersionKind,
    resources: Set[SupportedResource]
  ): String =
    resources
      .find(r => r.gvk == gvk)
      .map(_.plural)
      .getOrElse(gvk.kind)

  private def generateModel(
    rootPackage: Package,
    entityName: ScalaType,
    d: IdentifiedSchema,
    resources: Set[SupportedResource],
    definitionMap: Map[String, IdentifiedSchema]
  ): String = {
    import scala.meta._

    val encoderName = Pat.Var(Term.Name(entityName.name + "Encoder"))
    val decoderName = Pat.Var(Term.Name(entityName.name + "Decoder"))

    val entityFieldsT = Type.Name(entityName.name + "Fields")
    val entityFieldsInit = Init(entityFieldsT, Name.Anonymous(), List(List(q"Chunk.empty")))

    val defs: List[Stat] =
      Option(d.schema.getType) match {
        case Some("object") =>
          val objectSchema = d.schema.asInstanceOf[ObjectSchema]

          Option(objectSchema.getProperties).map(_.asScala) match {
            case Some(properties) =>
              val requiredProperties = Overrides.requiredFields(d)

              val props = properties
                .filterKeys(filterKeysOf(d))
                .toList
                .map { case (name, propSchema) =>
                  val isRequired = requiredProperties.contains(name)
                  val propT = toType(name, propSchema)

                  val nameN = Name(name)
                  if (isRequired)
                    param"""$nameN: ${propT.typ}"""
                  else
                    param"""$nameN: ${Types.optional(propT).typ} = Optional.Absent"""
                }

              val getters =
                properties
                  .filterKeys(filterKeysOf(d))
                  .toList
                  .map { case (name, propSchema) =>
                    val isRequired = requiredProperties.contains(name)
                    val propT = toType(name, propSchema)
                    val valueName = Term.Name(name)
                    val valueLit = Lit.String(name)
                    val getterName = Term.Name(s"get${name.capitalize}")

                    if (isRequired)
                      q"""def $getterName: IO[K8sFailure, ${propT.typ}] = ZIO.succeed($valueName)"""
                    else
                      q"""def $getterName: IO[K8sFailure, ${propT.typ}] = ZIO.fromEither($valueName.toRight(UndefinedField($valueLit)))"""
                  }

              val classDef =
                q"""case class ${entityName.typName}(..$props) {
                      ..$getters
                    }
                 """

              val encoder = {
                val baseJsonFields = properties
                  .filterKeys(filterKeysOf(d))
                  .map { case (k, _) =>
                    q"""${Lit.String(k)} := value.${Term.Name(k)}"""
                  }
                  .toList

                val jsonFields = d match {
                  case Regular(name, schema) =>
                    baseJsonFields
                  case d @ IdentifiedDefinition(
                        name,
                        GroupVersionKind(group, version, kind),
                        schema
                      ) =>
                    q""""kind" := ${Lit.String(kind)}""" ::
                      q""""apiVersion" := ${Lit.String(d.apiVersion)}""" ::
                      baseJsonFields
                }

                q"""implicit val $encoderName: Encoder[${entityName.typ}] =
                      (value: ${entityName.typ}) => Json.obj(
                        ..$jsonFields
                      )
                 """
              }

              val decoder = if (props.size > 22) {
                val propDecoders = properties
                  .filterKeys(filterKeysOf(d))
                  .map { case (k, propSchema) =>
                    val isRequired = requiredProperties.contains(k)
                    val propT =
                      if (isRequired)
                        toType(k, propSchema)
                      else
                        Types.optional(toType(k, propSchema))

                    val fieldLit = Lit.String(k)
                    enumerator"${Pat.Var(Term.Name(k))} <- cursor.downField($fieldLit).as[${propT.typ}]"
                  }
                  .toList
                val propTerms = properties.map { case (k, _) => Term.Name(k) }.toList

                q"""
                   implicit val $decoderName: Decoder[${entityName.typ}] =
                     (cursor: HCursor) =>
                      for { ..$propDecoders }
                      yield ${entityName.termName}(..$propTerms)
                 """
              } else {
                val forProductN = Term.Name("forProduct" + props.size)
                val propNameLits = properties
                  .filterKeys(filterKeysOf(d))
                  .map { case (k, _) => Lit.String(k) }
                  .toList

                q"""
                implicit val $decoderName: Decoder[${entityName.typ}] =
                  Decoder.$forProductN(..$propNameLits)(${entityName.termName}.apply)
                """
              }

              val k8sObject =
                d match {
                  case Regular(name, schema) =>
                    List.empty
                  case IdentifiedDefinition(
                        name,
                        gvk @ GroupVersionKind(group, version, kind),
                        schema
                      ) =>
                    val metadataT = properties.get("metadata").map(toType("metadata", _))
                    val metadataIsRequired = requiredProperties.contains("metadata")
                    val groupLit = Lit.String(group)
                    val versionLit = Lit.String(version)

                    val pluralLit = findPluralName(gvk, resources)
                    val kindLit = Lit.String(kind)
                    val apiVersionLit =
                      if (group.isEmpty)
                        Lit.String(version)
                      else
                        Lit.String(s"$group/$version")

                    val statusOps =
                      findStatusEntityOfSchema(schema.asInstanceOf[ObjectSchema]) match {
                        case Some(statusEntity) =>
                          val statusIsRequired = requiredProperties.contains("status")
                          List(
                            if (statusIsRequired)
                              q"""implicit val k8sObjectStatus: com.coralogix.zio.k8s.client.model.K8sObjectStatus[${entityName.typ}, ${statusEntity.typ}] =
                              new com.coralogix.zio.k8s.client.model.K8sObjectStatus[${entityName.typ}, ${statusEntity.typ}] {
                                def status(obj: ${entityName.typ}): Optional[${statusEntity.typ}] =
                                  Optional.Present(obj.status)
                                def mapStatus(f: ${statusEntity.typ} => ${statusEntity.typ})(obj: ${entityName.typ}): ${entityName.typ} =
                                  obj.copy(status = f(obj.status))
                              }
                              """
                            else
                              q"""implicit val k8sObjectStatus: com.coralogix.zio.k8s.client.model.K8sObjectStatus[${entityName.typ}, ${statusEntity.typ}] =
                              new com.coralogix.zio.k8s.client.model.K8sObjectStatus[${entityName.typ}, ${statusEntity.typ}] {
                                def status(obj: ${entityName.typ}): Optional[${statusEntity.typ}] =
                                  obj.status
                                def mapStatus(f: ${statusEntity.typ} => ${statusEntity.typ})(obj: ${entityName.typ}): ${entityName.typ} =
                                  obj.copy(status = obj.status.map(f))
                              }
                        """,
                            q"""implicit class StatusOps(protected val obj: ${entityName.typ})
                                extends com.coralogix.zio.k8s.client.model.K8sObjectStatusOps[${entityName.typ}, ${statusEntity.typ}] {
                                protected override val impl: com.coralogix.zio.k8s.client.model.K8sObjectStatus[${entityName.typ}, ${statusEntity.typ}] = k8sObjectStatus
                              }
                           """
                          )
                        case None               =>
                          List.empty
                      }

                    statusOps ++ ((metadataT, metadataIsRequired) match {
                      case (Some(t), false) if t.toString == "pkg.apis.meta.v1.ObjectMeta" =>
                        List(
                          q"""implicit val k8sObject: com.coralogix.zio.k8s.client.model.K8sObject[${entityName.typ}] =
                              new com.coralogix.zio.k8s.client.model.K8sObject[${entityName.typ}] {
                                def metadata(obj: ${entityName.typ}): Optional[pkg.apis.meta.v1.ObjectMeta] =
                                  obj.metadata
                                def mapMetadata(f: pkg.apis.meta.v1.ObjectMeta => pkg.apis.meta.v1.ObjectMeta)(obj: ${entityName.typ}): ${entityName.typ} =
                                  obj.copy(metadata = obj.metadata.map(f))
                              }
                        """,
                          q"""implicit class Ops(protected val obj: ${entityName.typ})
                                extends com.coralogix.zio.k8s.client.model.K8sObjectOps[${entityName.typ}] {
                                protected override val impl: com.coralogix.zio.k8s.client.model.K8sObject[${entityName.typ}] = k8sObject
                              }
                           """,
                          q"""implicit val resourceMetadata: ResourceMetadata[${entityName.typ}] =
                                new ResourceMetadata[${entityName.typ}] {
                                  override val kind: String = $kindLit
                                  override val apiVersion: String = $apiVersionLit
                                  override val resourceType: K8sResourceType = K8sResourceType($pluralLit, $groupLit, $versionLit)
                                }
                           """
                        )
                      case (Some(t), true) if t.toString == "pkg.apis.meta.v1.ObjectMeta"  =>
                        List(
                          q"""implicit val k8sObject: com.coralogix.zio.k8s.client.model.K8sObject[${entityName.typ}] =
                              new com.coralogix.zio.k8s.client.model.K8sObject[${entityName.typ}] {
                                def metadata(obj: ${entityName.typ}): Optional[pkg.apis.meta.v1.ObjectMeta] =
                                  Some(obj.metadata)
                                def mapMetadata(f: pkg.apis.meta.v1.ObjectMeta => pkg.apis.meta.v1.ObjectMeta)(obj: ${entityName.typ}): ${entityName.typ} =
                                  obj.copy(metadata = f(obj.metadata))
                              }
                        """,
                          q"""implicit class Ops(protected val obj: ${entityName.typ})
                                extends com.coralogix.zio.k8s.client.model.K8sObjectOps[${entityName.typ}] {
                                protected override val impl: com.coralogix.zio.k8s.client.model.K8sObject[${entityName.typ}] = k8sObject
                              }
                           """,
                          q"""implicit val resourceMetadata: ResourceMetadata[${entityName.typ}] =
                                new ResourceMetadata[${entityName.typ}] {
                                  override val kind: String = $kindLit
                                  override val apiVersion: String = $apiVersionLit
                                  override val resourceType: K8sResourceType = K8sResourceType($pluralLit, $groupLit, $versionLit)
                                }
                           """
                        )
                      case _                                                               =>
                        List.empty
                    })
                }

              val fieldSelectors =
                properties
                  .filterKeys(filterKeysOf(d))
                  .toList
                  .map { case (name, propSchema) =>
                    val propT = toType(name, propSchema)
                    val valueName = Term.Name(name)
                    val valueLit = Lit.String(name)

                    propT match {
                      case Type.Select(ns, Type.Name(n))
                          if refersToObject(definitionMap, propSchema) =>
                        val propN = Term.Select(ns, Term.Name(n))
                        val propFieldsT = Type.Select(ns, Type.Name(n + "Fields"))

                        q"""def $valueName: $propFieldsT = $propN.nestedField(_prefix :+ $valueLit)"""
                      case _ =>
                        q"""def $valueName: Field = com.coralogix.zio.k8s.client.model.field(_prefix :+ $valueLit)"""
                    }
                  }

              List(
                classDef,
                q"""
                      object ${entityName.termName} extends $entityFieldsInit {
                        def nestedField(prefix: Chunk[String]): $entityFieldsT = new $entityFieldsT(prefix)

                        $encoder
                        $decoder
                        ..$k8sObject
                      }""",
                q"""class $entityFieldsT(_prefix: Chunk[String]) {
                        ..$fieldSelectors
                    }
                 """
              )
            case None             =>
              q"""
                case class ${entityName.typName}(value: Json)
                object ${entityName.termName} {
                  implicit val $encoderName: Encoder[${entityName.typ}] = (v: ${entityName.typ}) => v.value
                  implicit val $decoderName: Decoder[${entityName.typ}] = (cursor: HCursor) => Right(${entityName.termName}(cursor.value))

                  def nestedField(prefix: Chunk[String]): $entityFieldsT = new $entityFieldsT(prefix)
                }

                class $entityFieldsT(_prefix: Chunk[String]) {
                  def field(subPath: String): Field = com.coralogix.zio.k8s.client.model.field(_prefix ++ Chunk.fromArray(subPath.split('.')))
                }
               """.stats
          }
        case Some("string") =>
          Option(d.schema.getFormat) match {
            case None                  =>
              q"""case class ${entityName.typName}(value: String) extends AnyVal
                  object ${entityName.termName} {
                    implicit val $encoderName: Encoder[${entityName.typ}] = Encoder.encodeString.contramap(_.value)
                    implicit val $decoderName: Decoder[${entityName.typ}] = Decoder.decodeString.map(${entityName.termName}.apply)
                  }
               """.stats
            case Some("int-or-string") =>
              q"""
                  case class ${entityName.typName}(value: Either[Int, String])
                  object ${entityName.termName} {
                    def fromInt(value: Int): ${entityName.typ} = ${entityName.termName}(Left(value))
                    def fromString(value: String): ${entityName.typ} = ${entityName.termName}(Right(value))

                    implicit val $encoderName: Encoder[${entityName.typ}] =
                      (value: ${entityName.typ}) => value.value match {
                        case Left(int) => Json.fromInt(int)
                        case Right(str) => Json.fromString(str)
                      }
                    implicit val $decoderName: Decoder[${entityName.typ}] =

                      (cursor: HCursor) => cursor.as[Int].map(v => ${entityName.termName}(Left(v))) match {
                        case Left(_) =>
                          cursor
                            .as[String]
                            .map(v => ${entityName.termName}(Right(v)))
                        case Right(value) => Right(value)
                      }
                  }
              """.stats
            case Some("date-time")     =>
              q"""case class ${entityName.typName}(value: OffsetDateTime) extends AnyVal
                  object ${entityName.termName} {
                    implicit val $encoderName: Encoder[${entityName.typ}] =
                      Encoder.encodeString.contramap(_.value.format(com.coralogix.zio.k8s.client.model.k8sDateTimeFormatter))
                    implicit val $decoderName: Decoder[${entityName.typ}] =
                      Decoder.decodeString.emapTry(str => Try(OffsetDateTime.parse(str, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)).map(${entityName.termName}.apply))
                  }
               """.stats
            case Some(other)           =>
              logger.error(s"!!! Unknown format for string alias: $other")
              List.empty
          }
        case _              =>
          logger.error(s"!!! Special type $entityName not handled yet")
          List.empty
      }

    val tree =
      q"""package ${entityName.pkg.term} {

          ..$defs
          }
      """

    val paramDescs =
      (Option(d.schema.getType).getOrElse("object")) match {
        case "object" =>
          val objectSchema = d.schema.asInstanceOf[ObjectSchema]
          val properties =
            Option(objectSchema.getProperties).map(_.asScala).getOrElse(Map.empty)

          val list = properties
            .filterKeys(filterKeysOf(d))
            .map { case (name, propSchema) =>
              val escapedName = name.replace("$", "$$")
              val desc = escapeDocString(
                Option(propSchema.getDescription)
                  .getOrElse("")
              )
              s"  * @param $escapedName $desc"
            }
            .mkString("\n")

          s"\n$list\n"
        case _        => ""
      }
    val classDesc =
      s"/**\n  * ${Option(d.schema.getDescription).getOrElse("")}\n$paramDescs */"

    val getterDocs =
      (Option(d.schema.getType).getOrElse("object")) match {
        case "object" =>
          val objectSchema = d.schema.asInstanceOf[ObjectSchema]
          val properties =
            Option(objectSchema.getProperties).map(_.asScala).getOrElse(Map.empty)
          val requiredProperties = Overrides.requiredFields(d)

          properties
            .filterKeys(filterKeysOf(d))
            .toList
            .map { case (name, propSchema) =>
              val from = s"def get${name.capitalize}:"
              val isRequired = requiredProperties.contains(name)
              val desc =
                escapeDocString(Option(propSchema.getDescription).getOrElse(s"Gets $name."))
              val replacement =
                if (isRequired)
                  s"/** $desc\n *\n * This effect always succeeds, it is safe to use the field [[$name]] directly.\n */\n$from"
                else
                  s"/** $desc\n *\n * If the field is not present, fails with [[com.coralogix.zio.k8s.client.UndefinedField]].\n */\n$from"

              from -> replacement
            }
            .toMap
        case _        => Map.empty
      }

    getterDocs.foldLeft(
      prettyPrint(tree)
        .replace("case class", classDesc + "\ncase class")
    ) { case (code, (from, to)) => code.replace(from, to) }
  }

  private def escapeDocString(s: String): String =
    s.replace("/*", "&#47;*")
      .replace("*/", "*&#47;")
      .replace("$", "$$")

  protected def toType(name: String, propSchema: Schema[_]): ScalaType =
    (Option(propSchema.getType), Option(propSchema.get$ref())) match {
      case (None, Some(ref)) =>
        splitName(ref.drop("#/components/schemas/".length))

      case (Some("string"), _) =>
        Option(propSchema.getFormat) match {
          case Some("byte")  =>
            t"Chunk[Byte]"
            Types.chunk(ScalaType.byte)
          case Some(unknown) =>
            logger.error(s"UNHANDLED STRING FORMAT for $name: $unknown")
            ScalaType.nothing
          case None          =>
            ScalaType.string
        }

      case (Some("boolean"), _) =>
        ScalaType.boolean
      case (Some("integer"), _) =>
        Option(propSchema.getFormat) match {
          case Some("int32") =>
            ScalaType.int
          case Some("int64") =>
            ScalaType.long
          case Some(unknown) =>
            logger.error(s"UNHANDLED INT FORMAT for $name: $unknown")
            ScalaType.nothing
          case None          =>
            ScalaType.int
        }
      case (Some("number"), _)  =>
        Option(propSchema.getFormat) match {
          case Some("double") =>
            ScalaType.double
          case Some(unknown)  =>
            logger.error(s"UNHANDLED NUMBER FORMAT for $name: $unknown")
            ScalaType.nothing
          case None           =>
            ScalaType.double
        }
      case (Some("array"), _)   =>
        val arraySchema = propSchema.asInstanceOf[ArraySchema]
        val itemType = toType(s"$name items", arraySchema.getItems)
        ScalaType.vector(itemType)

      case (Some("object"), _) =>
        Option(propSchema.getAdditionalProperties).map(_.asInstanceOf[Schema[_]]) match {
          case Some(additionalProperties) =>
            val valueType = toType(s"$name values", additionalProperties)
            ScalaType.map(ScalaType.string, valueType)
          case None                       =>
            logger.error(s"UNHANDLED object type for $name")
            ScalaType.nothing
        }

      case (Some(unknown), _) =>
        logger.error(s"!!! UNHANDLED TYPE for $name: $unknown")
        ScalaType.nothing
      case (None, None)       =>
        logger.error(s"!!! No type and no ref for $name")
        ScalaType.nothing
    }

  protected def filterKeysOf(d: IdentifiedSchema): String => Boolean =
    d match {
      case Regular(name, schema)                                                      =>
        (_: String) => true
      case IdentifiedDefinition(name, GroupVersionKind(group, version, kind), schema) =>
        (name: String) => name != "kind" && name != "apiVersion"
    }

  private def refersToObject(
    definitionMap: Map[String, IdentifiedSchema],
    schema: Schema[_]
  ): Boolean = {
    val name = schema.get$ref().drop("#/components/schemas/".length)
    definitionMap.get(name) match {
      case Some(d) =>
        d.schema.getType == "object"
      case None    =>
        println(s"Could not find $name in ${definitionMap.keySet}")
        false
    }

  }
}
