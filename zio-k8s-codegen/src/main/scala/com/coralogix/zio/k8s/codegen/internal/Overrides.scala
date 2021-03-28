package com.coralogix.zio.k8s.codegen.internal

import scala.collection.JavaConverters._

object Overrides {

  val forcedOptionals = Map(
    "io.k8s.api.events.v1.Event" -> Set("eventTime"),
    "io.k8s.api.core.v1.PodSpec" -> Set("containers")
  )

  def requiredFields(definition: IdentifiedSchema): Set[String] = {
    val requiredBySchema =
      Option(definition.schema.getRequired).map(_.asScala.toSet).getOrElse(Set.empty)
    forcedOptionals.get(definition.name) match {
      case Some(forcedOptionalFields) =>
        requiredBySchema.diff(forcedOptionalFields)
      case None                       =>
        requiredBySchema
    }
  }
}
