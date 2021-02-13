package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.Conversions.splitName
import io.swagger.v3.oas.models.media.ObjectSchema

trait Common {
  protected def findStatusEntity(
    definitions: Map[String, IdentifiedSchema],
    modelName: String
  ): Option[String] = {
    val modelSchema = definitions(modelName).schema.asInstanceOf[ObjectSchema]
    findStatusEntityOfSchema(modelSchema)
  }

  protected def findStatusEntityOfSchema(modelSchema: ObjectSchema): Option[String] =
    for {
      properties       <- Option(modelSchema.getProperties)
      statusPropSchema <- Option(properties.get("status"))
      ref              <- Option(statusPropSchema.get$ref())
      (pkg, name)       = splitName(ref.drop("#/components/schemas/".length))
    } yield pkg.mkString(".") + "." + name
}
