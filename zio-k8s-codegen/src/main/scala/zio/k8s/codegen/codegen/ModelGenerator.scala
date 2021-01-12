package zio.k8s.codegen.codegen

import io.swagger.v3.oas.models.media.{ ArraySchema, ObjectSchema, Schema }
import org.scalafmt.interfaces.Scalafmt
import sbt.util.Logger
import zio.ZIO
import zio.blocking.Blocking
import zio.k8s.codegen.CodegenIO.{ format, writeTextFile }
import zio.k8s.codegen.codegen.Conversions.splitName
import zio.nio.core.file.Path
import zio.nio.file.Files

import scala.collection.JavaConverters._
import scala.meta._

trait ModelGenerator {
  val modelRoot = Vector("zio", "k8s", "model")

  protected def generateAllModels(
    scalafmt: Scalafmt,
    log: Logger,
    targetRoot: Path,
    definitions: Set[IdentifiedSchema]
  ): ZIO[Blocking, Throwable, Set[Path]] = {
    val filteredDefinitions = definitions.filter(d => !isListModel(d))
    for {
      _ <- ZIO.effect(log.info(s"Generating code for ${filteredDefinitions.size} models..."))
      paths <- ZIO.foreach(filteredDefinitions) { d =>
                 val (groupName, entityName) = splitName(d.name)
                 val pkg = (modelRoot ++ groupName)

                 for {
                   _ <- ZIO.effect(log.info(s"Generating '$entityName' to $pkg"))
                   src       = generateModel(modelRoot, pkg, entityName, d)
                   targetDir = pkg.foldLeft(targetRoot)(_ / _)
                   _ <- Files.createDirectories(targetDir)
                   targetPath = targetDir / s"$entityName.scala"
                   _ <- writeTextFile(targetPath, src)
                   _ <- format(scalafmt, targetPath)
                 } yield targetPath
               }
    } yield paths
  }

  private def isListModel(model: IdentifiedSchema): Boolean =
    model.name.endsWith("List") // NOTE: better check: has 'metadata' field of type 'ListMeta'

  private def generateModel(
    rootPackage: Vector[String],
    pkg: Vector[String],
    entityName: String,
    d: IdentifiedSchema
  ): String = {
    import scala.meta._
    val rootPackageTerm = rootPackage.mkString(".").parse[Term].get.asInstanceOf[Term.Ref]
    val packageTerm = pkg.mkString(".").parse[Term].get.asInstanceOf[Term.Ref]

    val encoderName = Pat.Var(Term.Name(entityName + "Encoder"))
    val decoderName = Pat.Var(Term.Name(entityName + "Decoder"))

    val entityNameN = Term.Name(entityName)
    val entityNameT = Type.Name(entityName)

    val defs: List[Stat] =
      Option(d.schema.getType) match {
        case Some("object") =>
          val objectSchema = d.schema.asInstanceOf[ObjectSchema]

          Option(objectSchema.getProperties).map(_.asScala) match {
            case Some(properties) =>
              val requiredProperties =
                Option(objectSchema.getRequired)
                  .map(_.asScala.toSet)
                  .getOrElse(Set.empty) - "metadata"

              val props = properties
                .filterKeys(filterKeysOf(d))
                .toList
                .map {
                  case (name, propSchema) =>
                    val isRequired = requiredProperties.contains(name)
                    val propT = toType(name, propSchema)

                    val nameN = Name(name)
                    if (isRequired)
                      param"""$nameN: $propT"""
                    else
                      param"""$nameN: Option[$propT] = None"""
                }

              val classDef =
                d match {
                  case Regular(name, schema) =>
                    q"""case class $entityNameT(..$props)"""
                  case IdentifiedDefinition(name, group, kind, version, schema) =>
                    val metadataT = properties.get("metadata").map(toType("metadata", _))
                    val metadataIsRequired = requiredProperties.contains("metadata")

                    (metadataT, metadataIsRequired) match {
                      case (Some(t), false) if t.toString == "pkg.apis.meta.v1.ObjectMeta" =>
                        q"""case class $entityNameT(..$props)
                          extends zio.k8s.client.model.Object
                        """
                      case (Some(t), true) if t.toString == "pkg.apis.meta.v1.ObjectMeta" =>
                        q"""case class $entityNameT(..$props)
                              extends zio.k8s.client.model.Object {

                              override val metadata: Option[zio.k8s.model.pkg.apis.meta.v1.ObjectMeta] = Some(_metadata)
                            }
                        """
                      case _ =>
                        q"""case class $entityNameT(..$props)"""
                    }
                }

              val encoder = {
                val baseJsonFields = properties
                  .filterKeys(filterKeysOf(d))
                  .map {
                    case (k, _) =>
                      q"""${Lit.String(k)} := value.${Term.Name(k)}"""
                  }
                  .toList

                val jsonFields = d match {
                  case Regular(name, schema) =>
                    baseJsonFields
                  case d @ IdentifiedDefinition(name, group, kind, version, schema) =>
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
                  .map {
                    case (k, propSchema) =>
                      val isRequired = requiredProperties.contains(k)
                      val propT =
                        if (isRequired)
                          toType(k, propSchema)
                        else
                          t"Option[${toType(k, propSchema)}]"

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

              val transformations =
                d match {
                  case Regular(name, schema) =>
                    List.empty
                  case IdentifiedDefinition(name, group, kind, version, schema) =>
                    val metadataT = properties.get("metadata").map(toType("metadata", _))
                    val metadataIsRequired = requiredProperties.contains("metadata")

                    (metadataT, metadataIsRequired) match {
                      case (Some(t), false) if t.toString == "pkg.apis.meta.v1.ObjectMeta" =>
                        List(
                          q"""implicit val transformations: zio.k8s.client.model.ObjectTransformations[$entityNameT] =
                              new zio.k8s.client.model.ObjectTransformations[$entityNameT] {
                                def mapMetadata(f: pkg.apis.meta.v1.ObjectMeta => pkg.apis.meta.v1.ObjectMeta)(r: $entityNameT): $entityNameT =
                                  r.copy(metadata = r.metadata.map(f))
                              }
                        """
                        )
                      case (Some(t), true) if t.toString == "pkg.apis.meta.v1.ObjectMeta" =>
                        List(
                          q"""implicit val transformations: zio.k8s.client.model.ObjectTransformations[$entityNameT] =
                              new zio.k8s.client.model.ObjectTransformations[$entityNameT] {
                                def mapMetadata(f: pkg.apis.meta.v1.ObjectMeta => pkg.apis.meta.v1.ObjectMeta)(r: $entityNameT): $entityNameT =
                                  r.copy(metadata = f(r.metadata))
                              }
                        """
                        )
                      case _ =>
                        List.empty
                    }
                }

              List(
                classDef,
                q"""
                object $entityNameN {
                  $encoder
                  $decoder
                  ..$transformations
                }
               """
              )
            case None =>
              q"""
                case class $entityNameT(value: Json)
                object $entityNameN {
                  implicit val $encoderName: Encoder[$entityNameT] = (v: $entityNameT) => v.value
                  implicit val $decoderName: Decoder[$entityNameT] = (cursor: HCursor) => Right($entityNameN(cursor.value))
                }
               """.stats
          }
        case Some("string") =>
          Option(d.schema.getFormat) match {
            case None =>
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
                      (cursor: HCursor) => cursor.as[Int].map(v => $entityNameN(Left(v))) orElse cursor.as[String].map(v => $entityNameN(Right(v)))
                  }
              """.stats
            case Some("date-time") =>
              q"""case class $entityNameT(value: OffsetDateTime) extends AnyVal
                  object $entityNameN {
                    implicit val $encoderName: Encoder[$entityNameT] =
                      Encoder.encodeString.contramap(_.value.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    implicit val $decoderName: Decoder[$entityNameT] =
                      Decoder.decodeString.emapTry(str => Try(OffsetDateTime.parse(str, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)).map($entityNameN.apply))
                  }
               """.stats
            case Some(other) =>
              println(s"!!! Unknown format for string alias: $other")
              List.empty
          }
        case _ =>
          println(s"!!! Special type $entityName not handled yet")
          List.empty
      }

    val tree =
      q"""package $packageTerm {

          import zio.Chunk
          import io.circe._
          import io.circe.syntax._
          import java.time.OffsetDateTime
          import scala.util.Try

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
            .map {
              case (name, propSchema) =>
                val desc = Option(propSchema.getDescription)
                  .getOrElse("")
                  .replace("/*", "&#47;*")
                  .replace("*/", "*&#47;")
                s"  * @param $name $desc"
            }
            .mkString("\n")

          s"\n$list\n"
        case _ => ""
      }
    val classDesc =
      s"/**\n  * ${Option(d.schema.getDescription).getOrElse("")}\n$paramDescs */"

    tree.toString
      .replace("case class", classDesc + "\ncase class")
  }

  private def toType(name: String, propSchema: Schema[_]): Type =
    (Option(propSchema.getType), Option(propSchema.get$ref())) match {
      case (None, Some(ref)) =>
        val (nsParts, n) = splitName(ref.drop("#/components/schemas/".length))
        val ns = nsParts.mkString(".").parse[Term].get.asInstanceOf[Term.Ref]
        Type.Select(ns, Type.Name(n))

      case (Some("string"), _) =>
        Option(propSchema.getFormat) match {
          case Some("byte") =>
            t"Chunk[Byte]"
          case Some(unknown) =>
            println(s"!!! UNHANDLED STRING FORMAT for $name: $unknown")
            t"String"
          case None =>
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
            println(s"!!! UNHANDLED INT FORMAT for $name: $unknown")
            t"Int"
          case None =>
            t"Int"
        }
      case (Some("number"), _) =>
        Option(propSchema.getFormat) match {
          case Some("double") =>
            t"Double"
          case Some(unknown) =>
            println(s"!!! UNHANDLED NUMBER FORMAT for $name: $unknown")
            t"Double"
          case None =>
            t"Double"
        }
      case (Some("array"), _) =>
        val arraySchema = propSchema.asInstanceOf[ArraySchema]
        val itemType = toType(s"$name items", arraySchema.getItems)
        t"Vector[$itemType]"

      case (Some("object"), _) =>
        Option(propSchema.getAdditionalProperties).map(_.asInstanceOf[Schema[_]]) match {
          case Some(additionalProperties) =>
            val keyType = toType(s"$name values", additionalProperties)
            t"Map[String, $keyType]"
          case None =>
            println(s"!!! UNHANDLED object type for $name")
            t"AnyRef"
        }

      case (Some(unknown), _) =>
        println(s"!!! UNHANDLED TYPE for $name: $unknown")
        t"AnyRef"
      case (None, None) =>
        println(s"!!! No type and no ref for $name")
        t"AnyRef"
    }

  private def filterKeysOf(d: IdentifiedSchema) =
    d match {
      case Regular(name, schema) =>
        (_: String) => true
      case IdentifiedDefinition(name, group, kind, version, schema) =>
        (name: String) => name != "kind" && name != "apiVersion"
    }

}
