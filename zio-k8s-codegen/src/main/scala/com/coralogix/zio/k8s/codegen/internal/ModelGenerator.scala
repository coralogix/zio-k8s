package com.coralogix.zio.k8s.codegen.internal

import io.swagger.v3.oas.models.media.{ ArraySchema, ObjectSchema, Schema }
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.ZIO
import zio.blocking.Blocking
import com.coralogix.zio.k8s.codegen.internal.CodegenIO.{ format, writeTextFile }
import com.coralogix.zio.k8s.codegen.internal.Conversions.splitName
import zio.nio.core.file.Path
import zio.nio.file.Files

import scala.collection.JavaConverters._
import scala.meta._

trait ModelGenerator {
  this: Common =>

  val modelRoot = Vector("com", "coralogix", "zio", "k8s", "model")
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
                 val (groupName, entityName) = splitName(d.name)
                 val pkg = (modelRoot ++ groupName)

                 for {
                   _         <- ZIO.effect(logger.info(s"Generating '$entityName' to ${pkg.mkString(".")}"))
                   src        = generateModel(modelRoot, pkg, entityName, d, resources, definitionMap)
                   targetDir  = pkg.foldLeft(targetRoot)(_ / _)
                   _         <- Files.createDirectories(targetDir)
                   targetPath = targetDir / s"$entityName.scala"
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
    rootPackage: Vector[String],
    pkg: Vector[String],
    entityName: String,
    d: IdentifiedSchema,
    resources: Set[SupportedResource],
    definitionMap: Map[String, IdentifiedSchema]
  ): String = {
    import scala.meta._
    val rootPackageTerm = rootPackage.mkString(".").parse[Term].get.asInstanceOf[Term.Ref]
    val packageTerm = pkg.mkString(".").parse[Term].get.asInstanceOf[Term.Ref]

    val encoderName = Pat.Var(Term.Name(entityName + "Encoder"))
    val decoderName = Pat.Var(Term.Name(entityName + "Decoder"))

    val entityNameN = Term.Name(entityName)
    val entityNameT = Type.Name(entityName)
    val entityFieldsT = Type.Name(entityName + "Fields")
    val entityFieldsInit = Init(entityFieldsT, Name.Anonymous(), List(List(q"Chunk.empty[String]")))

    val defs: List[Stat] =
      Option(d.schema.getType) match {
        case Some("object") =>
          val objectSchema = d.schema.asInstanceOf[ObjectSchema]

          Option(objectSchema.getProperties).map(_.asScala) match {
            case Some(properties) =>
              val requiredProperties =
                Option(objectSchema.getRequired)
                  .map(_.asScala.toSet)
                  .getOrElse(Set.empty)

              val props = properties
                .filterKeys(filterKeysOf(d))
                .toList
                .map { case (name, propSchema) =>
                  val isRequired = requiredProperties.contains(name)
                  val propT = toType(name, propSchema)

                  val nameN = Name(name)
                  if (isRequired)
                    param"""$nameN: $propT"""
                  else
                    param"""$nameN: Optional[$propT] = Optional.Absent"""
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
                      q"""def $getterName: IO[K8sFailure, $propT] = ZIO.succeed($valueName)"""
                    else
                      q"""def $getterName: IO[K8sFailure, $propT] = ZIO.fromEither($valueName.toRight(UndefinedField($valueLit)))"""
                  }

              val classDef =
                q"""case class $entityNameT(..$props) {
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

                q"""implicit val $encoderName: Encoder[$entityNameT] =
                      (value: $entityNameT) => Json.obj(
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
                        t"Optional[${toType(k, propSchema)}]"

                    val fieldLit = Lit.String(k)
                    enumerator"${Pat.Var(Term.Name(k))} <- cursor.downField($fieldLit).as[$propT]"
                  }
                  .toList
                val propTerms = properties.map { case (k, _) => Term.Name(k) }.toList

                q"""
                   implicit val $decoderName: Decoder[$entityNameT] =
                     (cursor: HCursor) =>
                      for { ..$propDecoders }
                      yield $entityNameN(..$propTerms)
                 """
              } else {
                val forProductN = Term.Name("forProduct" + props.size)
                val propNameLits = properties
                  .filterKeys(filterKeysOf(d))
                  .map { case (k, _) => Lit.String(k) }
                  .toList

                q"""
                implicit val $decoderName: Decoder[$entityNameT] =
                  Decoder.$forProductN(..$propNameLits)($entityNameN.apply)
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
                        Lit.String(s"${group}/${version}")

                    val statusOps =
                      findStatusEntityOfSchema(schema.asInstanceOf[ObjectSchema]) match {
                        case Some(statusEntity) =>
                          val statusT = s"com.coralogix.zio.k8s.model.$statusEntity".parse[Type].get
                          val statusIsRequired = requiredProperties.contains("status")
                          List(
                            if (statusIsRequired)
                              q"""implicit val k8sObjectStatus: com.coralogix.zio.k8s.client.model.K8sObjectStatus[$entityNameT, $statusT] =
                              new com.coralogix.zio.k8s.client.model.K8sObjectStatus[$entityNameT, $statusT] {
                                def status(obj: $entityNameT): Optional[$statusT] =
                                  Optional.Present(obj.status)
                                def mapStatus(f: $statusT => $statusT)(obj: $entityNameT): $entityNameT =
                                  obj.copy(status = f(obj.status))
                              }
                              """
                            else
                              q"""implicit val k8sObjectStatus: com.coralogix.zio.k8s.client.model.K8sObjectStatus[$entityNameT, $statusT] =
                              new com.coralogix.zio.k8s.client.model.K8sObjectStatus[$entityNameT, $statusT] {
                                def status(obj: $entityNameT): Optional[$statusT] =
                                  obj.status
                                def mapStatus(f: $statusT => $statusT)(obj: $entityNameT): $entityNameT =
                                  obj.copy(status = obj.status.map(f))
                              }
                        """,
                            q"""implicit class StatusOps(protected val obj: $entityNameT)
                                extends com.coralogix.zio.k8s.client.model.K8sObjectStatusOps[$entityNameT, $statusT] {
                                protected override val impl: com.coralogix.zio.k8s.client.model.K8sObjectStatus[$entityNameT, $statusT] = k8sObjectStatus
                              }
                           """
                          )
                        case None               =>
                          List.empty
                      }

                    statusOps ++ ((metadataT, metadataIsRequired) match {
                      case (Some(t), false) if t.toString == "pkg.apis.meta.v1.ObjectMeta" =>
                        List(
                          q"""implicit val k8sObject: com.coralogix.zio.k8s.client.model.K8sObject[$entityNameT] =
                              new com.coralogix.zio.k8s.client.model.K8sObject[$entityNameT] {
                                def metadata(obj: $entityNameT): Optional[pkg.apis.meta.v1.ObjectMeta] =
                                  obj.metadata
                                def mapMetadata(f: pkg.apis.meta.v1.ObjectMeta => pkg.apis.meta.v1.ObjectMeta)(obj: $entityNameT): $entityNameT =
                                  obj.copy(metadata = obj.metadata.map(f))
                              }
                        """,
                          q"""implicit class Ops(protected val obj: $entityNameT)
                                extends com.coralogix.zio.k8s.client.model.K8sObjectOps[$entityNameT] {
                                protected override val impl: com.coralogix.zio.k8s.client.model.K8sObject[$entityNameT] = k8sObject
                              }
                           """,
                          q"""implicit val resourceMetadata: ResourceMetadata[$entityNameT] =
                                new ResourceMetadata[$entityNameT] {
                                  override val kind: String = $kindLit
                                  override val apiVersion: String = $apiVersionLit
                                  override val resourceType: K8sResourceType = K8sResourceType($pluralLit, $groupLit, $versionLit)
                                }
                           """
                        )
                      case (Some(t), true) if t.toString == "pkg.apis.meta.v1.ObjectMeta"  =>
                        List(
                          q"""implicit val k8sObject: com.coralogix.zio.k8s.client.model.K8sObject[$entityNameT] =
                              new com.coralogix.zio.k8s.client.model.K8sObject[$entityNameT] {
                                def metadata(obj: $entityNameT): Optional[pkg.apis.meta.v1.ObjectMeta] =
                                  Some(obj.metadata)
                                def mapMetadata(f: pkg.apis.meta.v1.ObjectMeta => pkg.apis.meta.v1.ObjectMeta)(obj: $entityNameT): $entityNameT =
                                  obj.copy(metadata = f(obj.metadata))
                              }
                        """,
                          q"""implicit class Ops(protected val obj: $entityNameT)
                                extends com.coralogix.zio.k8s.client.model.K8sObjectOps[$entityNameT] {
                                protected override val impl: com.coralogix.zio.k8s.client.model.K8sObject[$entityNameT] = k8sObject
                              }
                           """,
                          q"""implicit val resourceMetadata: ResourceMetadata[$entityNameT] =
                                new ResourceMetadata[$entityNameT] {
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
                      object $entityNameN extends $entityFieldsInit {
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
                case class $entityNameT(value: Json)
                object $entityNameN {
                  implicit val $encoderName: Encoder[$entityNameT] = (v: $entityNameT) => v.value
                  implicit val $decoderName: Decoder[$entityNameT] = (cursor: HCursor) => Right($entityNameN(cursor.value))

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
              q"""case class $entityNameT(value: String) extends AnyVal
                  object $entityNameN {
                    implicit val $encoderName: Encoder[$entityNameT] = Encoder.encodeString.contramap(_.value)
                    implicit val $decoderName: Decoder[$entityNameT] = Decoder.decodeString.map($entityNameN.apply)
                  }
               """.stats
            case Some("int-or-string") =>
              q"""
                  case class $entityNameT(value: Either[Int, String])
                  object $entityNameN {
                    def fromInt(value: Int): $entityNameT = $entityNameN(Left(value))
                    def fromString(value: String): $entityNameT = $entityNameN(Right(value))

                    implicit val $encoderName: Encoder[$entityNameT] =
                      (value: $entityNameT) => value.value match {
                        case Left(int) => Json.fromInt(int)
                        case Right(str) => Json.fromString(str)
                      }
                    implicit val $decoderName: Decoder[$entityNameT] =

                      (cursor: HCursor) => cursor.as[Int].map(v => $entityNameN(Left(v))) match {
                        case Left(_) =>
                          cursor
                            .as[String]
                            .map(v => $entityNameN(Right(v)))
                        case Right(value) => Right(value)
                      }
                  }
              """.stats
            case Some("date-time")     =>
              q"""case class $entityNameT(value: OffsetDateTime) extends AnyVal
                  object $entityNameN {
                    implicit val $encoderName: Encoder[$entityNameT] =
                      Encoder.encodeString.contramap(_.value.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    implicit val $decoderName: Decoder[$entityNameT] =
                      Decoder.decodeString.emapTry(str => Try(OffsetDateTime.parse(str, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)).map($entityNameN.apply))
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
      q"""package $packageTerm {

          import io.circe._
          import io.circe.syntax._
          import java.time.OffsetDateTime
          import scala.util.Try
          import zio.{Chunk, IO, ZIO}

          import com.coralogix.zio.k8s.client.{K8sFailure, UndefinedField}
          import com.coralogix.zio.k8s.client.model.{Field, K8sResourceType, Optional, ResourceMetadata}

          import $rootPackageTerm._

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
              val desc = Option(propSchema.getDescription)
                .getOrElse("")
                .replace("/*", "&#47;*")
                .replace("*/", "*&#47;")
              s"  * @param $name $desc"
            }
            .mkString("\n")

          s"\n$list\n"
        case _        => ""
      }
    val classDesc =
      s"/**\n  * ${Option(d.schema.getDescription).getOrElse("")}\n$paramDescs */"

    tree.toString
      .replace("case class", classDesc + "\ncase class")
  }

  protected def toType(name: String, propSchema: Schema[_]): Type =
    (Option(propSchema.getType), Option(propSchema.get$ref())) match {
      case (None, Some(ref)) =>
        val (nsParts, n) = splitName(ref.drop("#/components/schemas/".length))
        val ns = nsParts.mkString(".").parse[Term].get.asInstanceOf[Term.Ref]
        Type.Select(ns, Type.Name(n))

      case (Some("string"), _) =>
        Option(propSchema.getFormat) match {
          case Some("byte")  =>
            t"Chunk[Byte]"
          case Some(unknown) =>
            logger.error(s"UNHANDLED STRING FORMAT for $name: $unknown")
            t"CodeGeneratorError"
          case None          =>
            t"String"
        }

      case (Some("boolean"), _) =>
        t"Boolean"
      case (Some("integer"), _) =>
        Option(propSchema.getFormat) match {
          case Some("int32") =>
            t"Int"
          case Some("int64") =>
            t"Long"
          case Some(unknown) =>
            logger.error(s"UNHANDLED INT FORMAT for $name: $unknown")
            t"CodeGeneratorError"
          case None          =>
            t"Int"
        }
      case (Some("number"), _)  =>
        Option(propSchema.getFormat) match {
          case Some("double") =>
            t"Double"
          case Some(unknown)  =>
            logger.error(s"UNHANDLED NUMBER FORMAT for $name: $unknown")
            t"CodeGeneratorError"
          case None           =>
            t"Double"
        }
      case (Some("array"), _)   =>
        val arraySchema = propSchema.asInstanceOf[ArraySchema]
        val itemType = toType(s"$name items", arraySchema.getItems)
        t"Vector[$itemType]"

      case (Some("object"), _) =>
        Option(propSchema.getAdditionalProperties).map(_.asInstanceOf[Schema[_]]) match {
          case Some(additionalProperties) =>
            val keyType = toType(s"$name values", additionalProperties)
            t"Map[String, $keyType]"
          case None                       =>
            logger.error(s"UNHANDLED object type for $name")
            t"CodeGeneratorError"
        }

      case (Some(unknown), _) =>
        logger.error(s"!!! UNHANDLED TYPE for $name: $unknown")
        t"CodeGeneratorError"
      case (None, None)       =>
        logger.error(s"!!! No type and no ref for $name")
        t"CodeGeneratorError"
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
