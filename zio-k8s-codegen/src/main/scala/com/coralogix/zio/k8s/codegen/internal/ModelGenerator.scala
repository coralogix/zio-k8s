package com.coralogix.zio.k8s.codegen.internal

import io.github.vigoo.metagen.core.*
import io.swagger.v3.oas.models.media.{ ArraySchema, ObjectSchema, Schema }
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.ZIO
import com.coralogix.zio.k8s.codegen.internal.CodegenIO.writeTextFile
import com.coralogix.zio.k8s.codegen.internal.Conversions.{ splitName, splitNameOld }
import zio.nio.file.Path
import zio.nio.file.Files

import scala.collection.JavaConverters.*
import scala.meta.*
import scala.meta.Term.ArgClause

trait ModelGenerator {
  this: Common =>

  val modelRootPkg = Package("com", "coralogix", "zio", "k8s", "model") // TODO: rename
  val modelRoot = Vector("com", "coralogix", "zio", "k8s", "model") // TODO: remove
  def logger: sbt.Logger

  protected def generateAllModels(
    definitionMap: Map[String, IdentifiedSchema],
    resources: Set[SupportedResource]
  ): ZIO[Generator, GeneratorFailure[Throwable], Set[Path]] = {
    val filteredDefinitions = definitionMap.values.filter(d => !isListModel(d)).toSet
    for {
      _     <- ZIO.succeed(logger.info(s"Generating code for ${filteredDefinitions.size} models..."))
      paths <- ZIO.foreach(filteredDefinitions) { d =>
                 val entity = splitName(d.name)

                 for {
                   _          <-
                     ZIO.succeed(
                       logger.info(s"Generating '${entity.name}' to ${entity.pkg.asString}")
                     )
                   targetPath <-
                     Generator.generateScalaPackage[Any, Nothing](entity.pkg, entity.name) {
                       generateModel(modelRootPkg, entity, d, resources, definitionMap)
                     }
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
    entity: ScalaType,
    d: IdentifiedSchema,
    resources: Set[SupportedResource],
    definitionMap: Map[String, IdentifiedSchema]
  ): ZIO[CodeFileGenerator, Nothing, Term.Block] = {
    import scala.meta._

    val encoderName = Pat.Var(Term.Name(entity.name + "Encoder"))
    val decoderName = Pat.Var(Term.Name(entity.name + "Decoder"))
    val entityFieldsT = Type.Name(entity.name + "Fields")
    val entityFieldsInit =
      Init(entityFieldsT, Name.Anonymous(), List(ArgClause(List(q"Chunk.empty"), None)))

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
                  val prop = toType(name, propSchema)

                  val nameN = Name(name)
                  if (isRequired)
                    param"""$nameN: ${prop.typ}"""
                  else
                    param"""$nameN: ${Types.optional(prop).typ} = ${Types.optionalAbsent.term}"""
                }

              val getters =
                properties
                  .filterKeys(filterKeysOf(d))
                  .toList
                  .map { case (name, propSchema) =>
                    val isRequired = requiredProperties.contains(name)
                    val prop = toType(name, propSchema)
                    val valueName = Term.Name(name)
                    val valueLit = Lit.String(name)
                    val getterName = Term.Name(s"get${name.capitalize}")

                    if (isRequired)
                      q"""def $getterName: ${Types.k8sIO(prop).typ} = ZIO.succeed($valueName)"""
                    else
                      q"""def $getterName: ${Types
                          .k8sIO(prop)
                          .typ} = ZIO.fromEither($valueName.toRight(UndefinedField($valueLit)))"""
                  }

              val classDef =
                q"""case class ${entity.typName}(..$props) {
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

                q"""implicit val $encoderName: ${Types.jsonEncoder(entity).typ} =
                      (value: ${entity.typ}) => Json.obj(
                        ..$jsonFields
                      )
                 """
              }

              val decoder = if (props.size > 22) {
                val propDecoders = properties
                  .filterKeys(filterKeysOf(d))
                  .map { case (k, propSchema) =>
                    val isRequired = requiredProperties.contains(k)
                    val prop =
                      if (isRequired)
                        toType(k, propSchema)
                      else
                        Types.optional(toType(k, propSchema))

                    val fieldLit = Lit.String(k)
                    enumerator"${Pat.Var(Term.Name(k))} <- cursor.downField($fieldLit).as[${prop.typ}]"
                  }
                  .toList
                val propTerms = properties.map { case (k, _) => Term.Name(k) }.toList

                q"""
                   implicit val $decoderName: ${Types.jsonDecoder(entity).typ} =
                     (cursor: HCursor) =>
                      for { ..$propDecoders }
                      yield ${entity.termName}(..$propTerms)
                 """
              } else {
                val forProductN = Term.Name("forProduct" + props.size)
                val propNameLits = properties
                  .filterKeys(filterKeysOf(d))
                  .map { case (k, _) => Lit.String(k) }
                  .toList

                q"""
                implicit val $decoderName: ${Types.jsonDecoder(entity).typ} =
                  Decoder.$forProductN(..$propNameLits)(${entity.termName}.apply)
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
                    val metadata = properties.get("metadata").map(toType("metadata", _))
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
                          val statusT = s"com.coralogix.zio.k8s.model.$statusEntity".parse[Type].get
                          val statusIsRequired = requiredProperties.contains("status")
                          List(
                            if (statusIsRequired)
                              q"""implicit val k8sObjectStatus: com.coralogix.zio.k8s.client.model.K8sObjectStatus[${entity.typ}, ${statusEntity.typ}] =
                              new com.coralogix.zio.k8s.client.model.K8sObjectStatus[${entity.typ}, ${statusEntity.typ}] {
                                def status(obj: ${entity.typ}): Optional[${statusEntity.typ}] =
                                  Optional.Present(obj.status)
                                def mapStatus(f: ${statusEntity.typ} => ${statusEntity.typ})(obj: ${entity.typ}): ${entity.typ} =
                                  obj.copy(status = f(obj.status))
                              }
                              """
                            else
                              q"""implicit val k8sObjectStatus: com.coralogix.zio.k8s.client.model.K8sObjectStatus[${entity.typ}, ${statusEntity.typ}] =
                              new com.coralogix.zio.k8s.client.model.K8sObjectStatus[${entity.typ}, ${statusEntity.typ}] {
                                def status(obj: ${entity.typ}): Optional[${statusEntity.typ}] =
                                  obj.status
                                def mapStatus(f: ${statusEntity.typ} => ${statusEntity.typ})(obj: ${entity.typ}): ${entity.typ} =
                                  obj.copy(status = obj.status.map(f))
                              }
                        """,
                            q"""implicit class StatusOps(protected val obj: ${entity.typ})
                                extends com.coralogix.zio.k8s.client.model.K8sObjectStatusOps[${entity.typ}, ${statusEntity.typ}] {
                                protected override val impl: com.coralogix.zio.k8s.client.model.K8sObjectStatus[${entity.typ}, ${statusEntity.typ}] = k8sObjectStatus
                              }
                           """
                          )
                        case None               =>
                          List.empty
                      }

                    statusOps ++ ((metadata, metadataIsRequired) match {
                      case (Some(t), false) if t.asString == "pkg.apis.meta.v1.ObjectMeta" =>
                        List(
                          q"""implicit val k8sObject: com.coralogix.zio.k8s.client.model.K8sObject[${entity.typ}] =
                              new com.coralogix.zio.k8s.client.model.K8sObject[${entity.typ}] {
                                def metadata(obj: ${entity.typ}): Optional[pkg.apis.meta.v1.ObjectMeta] =
                                  obj.metadata
                                def mapMetadata(f: pkg.apis.meta.v1.ObjectMeta => pkg.apis.meta.v1.ObjectMeta)(obj: ${entity.typ}): ${entity.typ} =
                                  obj.copy(metadata = obj.metadata.map(f))
                              }
                        """,
                          q"""implicit class Ops(protected val obj: ${entity.typ})
                                extends com.coralogix.zio.k8s.client.model.K8sObjectOps[${entity.typ}] {
                                protected override val impl: com.coralogix.zio.k8s.client.model.K8sObject[${entity.typ}] = k8sObject
                              }
                           """,
                          q"""implicit val resourceMetadata: ResourceMetadata[${entity.typ}] =
                                new ResourceMetadata[${entity.typ}] {
                                  override val kind: String = $kindLit
                                  override val apiVersion: String = $apiVersionLit
                                  override val resourceType: K8sResourceType = K8sResourceType($pluralLit, $groupLit, $versionLit)
                                }
                           """
                        )
                      case (Some(t), true) if t.asString == "pkg.apis.meta.v1.ObjectMeta"  =>
                        List(
                          q"""implicit val k8sObject: com.coralogix.zio.k8s.client.model.K8sObject[${entity.typ}] =
                              new com.coralogix.zio.k8s.client.model.K8sObject[${entity.typ}] {
                                def metadata(obj: ${entity.typ}): Optional[pkg.apis.meta.v1.ObjectMeta] =
                                  Some(obj.metadata)
                                def mapMetadata(f: pkg.apis.meta.v1.ObjectMeta => pkg.apis.meta.v1.ObjectMeta)(obj: ${entity.typ}): ${entity.typ} =
                                  obj.copy(metadata = f(obj.metadata))
                              }
                        """,
                          q"""implicit class Ops(protected val obj: ${entity.typ})
                                extends com.coralogix.zio.k8s.client.model.K8sObjectOps[${entity.typ}] {
                                protected override val impl: com.coralogix.zio.k8s.client.model.K8sObject[${entity.typ}] = k8sObject
                              }
                           """,
                          q"""implicit val resourceMetadata: ResourceMetadata[${entity.typ}] =
                                new ResourceMetadata[${entity.typ}] {
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
                    val prop = toType(name, propSchema)
                    val valueName = Term.Name(name)
                    val valueLit = Lit.String(name)

                    prop match {
                      case ScalaType(pkg, name, _)
                          if pkg != Package.scala && refersToObject(definitionMap, propSchema) =>
                        val propFields = ScalaType(pkg, name + "Fields")

                        q"""def $valueName: $propFields = $propN.nestedField(_prefix :+ $valueLit)"""
                      case _ =>
                        q"""def $valueName: Field = com.coralogix.zio.k8s.client.model.field(_prefix :+ $valueLit)"""
                    }
                  }

              List(
                classDef,
                q"""
                      object ${entity.termName} extends $entityFieldsInit {
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
                case class ${entity.typName}(value: Json)
                object ${entity.termName} {
                  implicit val $encoderName: ${Types
                  .jsonEncoder(entity)
                  .typ} = (v: ${entity.typ}) => v.value
                  implicit val $decoderName: ${Types
                  .jsonDecoder(entity)
                  .typ} = (cursor: HCursor) => Right(${entity.term}(cursor.value))

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
              q"""case class ${entity.typName}(value: String) extends AnyVal
                  object ${entity.termName} {
                    implicit val $encoderName: ${Types
                  .jsonEncoder(entity)
                  .typ} = Encoder.encodeString.contramap(_.value)
                    implicit val $decoderName: ${Types
                  .jsonDecoder(entity)
                  .typ} = Decoder.decodeString.map(${entity.term}.apply)
                  }
               """.stats
            case Some("int-or-string") =>
              q"""
                  case class ${entity.typName}(value: Either[Int, String])
                  object ${entity.termName} {
                    def fromInt(value: Int): ${entity.typ} = ${entity.term}(Left(value))
                    def fromString(value: String): ${entity.typ} = ${entity.term}(Right(value))

                    implicit val $encoderName: ${Types.jsonEncoder(entity).typ} =
                      (value: ${entity.typ}) => value.value match {
                        case Left(int) => Json.fromInt(int)
                        case Right(str) => Json.fromString(str)
                      }
                    implicit val $decoderName: ${Types.jsonDecoder(entity).typ} =

                      (cursor: HCursor) => cursor.as[Int].map(v => ${entity.term}(Left(v))) match {
                        case Left(_) =>
                          cursor
                            .as[String]
                            .map(v => ${entity.term}(Right(v)))
                        case Right(value) => Right(value)
                      }
                  }
              """.stats
            case Some("date-time")     =>
              q"""case class ${entity.typName}(value: OffsetDateTime) extends AnyVal
                  object ${entity.termName} {
                    implicit val $encoderName: ${Types.jsonEncoder(entity).typ} =
                      Encoder.encodeString.contramap(_.value.format(com.coralogix.zio.k8s.client.model.k8sDateTimeFormatter))
                    implicit val $decoderName: ${Types.jsonDecoder(entity).typ} =
                      Decoder.decodeString.emapTry(str => Try(OffsetDateTime.parse(str, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)).map(${entity.term}.apply))
                  }
               """.stats
            case Some(other)           =>
              logger.error(s"!!! Unknown format for string alias: $other")
              List.empty
          }
        case _              =>
          logger.error(s"!!! Special type ${entity.name} not handled yet")
          List.empty
      }

    val tree = Term.Block(defs)

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

    ZIO.succeed(tree)

    // TODO: docs support
//    getterDocs.foldLeft(
//      prettyPrint(tree)
//        .replace("case class", classDesc + "\ncase class")
//    ) { case (code, (from, to)) => code.replace(from, to) }
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
