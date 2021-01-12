package zio.k8s.codegen.codegen

import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.parameters.Parameter

import scala.collection.JavaConverters._

sealed trait EndpointType
object EndpointType {
  case class List(namespaced: Boolean, detectedPlural: String, supportsWatch: Boolean)
      extends EndpointType
  case class Get(namespaced: Boolean, detectedPlural: String) extends EndpointType
  case class Post(namespaced: Boolean, detectedPlural: String) extends EndpointType
  case class Put(namespaced: Boolean, detectedPlural: String, modelName: String)
      extends EndpointType
  case class PutStatus(namespaced: Boolean, detectedPlural: String) extends EndpointType
  case class Delete(namespaced: Boolean, detectedPlural: String) extends EndpointType

  case class Unsupported(reason: String) extends EndpointType

  def detectEndpointType(endpoint: IdentifiedAction): EndpointType = {
    val outer = endpoint.outerParameters
    val inner = Option(endpoint.op.getParameters).map(_.asScala.toList).getOrElse(scala.List.empty)
    val bodyType =
      for {
        requestBody  <- Option(endpoint.op.getRequestBody)
        content      <- Option(requestBody.getContent)
        firstContent <- content.asScala.values.headOption
        schema       <- Option(firstContent.getSchema)
        ref          <- Option(schema.get$ref())
      } yield ref.drop("#/components/schemas/".length)

    val containsNamespace = containsParameterDefinition(outer, inner, "namespace")
    val containsName = containsParameterDefinition(outer, inner, "name")

    endpoint.action match {
      case "list" =>
        val containsLimit = containsParameterDefinition(outer, inner, "limit")
        val containsContinue = containsParameterDefinition(outer, inner, "continue")
        val containsWatch = containsParameterDefinition(outer, inner, "watch")
        val containsResourceVersion = containsParameterDefinition(outer, inner, "resourceVersion")
        val supportsWatch = containsWatch && containsResourceVersion

        val clusterPattern =
          if (endpoint.group.isEmpty)
            s"""/api/${endpoint.version}/([a-z]+)""".r
          else
            s"""/apis/${endpoint.group}/${endpoint.version}/([a-z]+)""".r

        val namespacedPattern =
          if (endpoint.group.isEmpty)
            s"""/api/${endpoint.version}/namespaces/\\{namespace\\}/([a-z]+)""".r
          else
            s"""/apis/${endpoint.group}/${endpoint.version}/namespaces/\\{namespace\\}/([a-z]+)""".r

        if (endpoint.method == PathItem.HttpMethod.GET)
          if (containsLimit)
            if (containsContinue)
              endpoint.name match {
                case clusterPattern(plural) =>
                  if (containsNamespace)
                    EndpointType.Unsupported(
                      "Matches cluster pattern but contains namespace parameter"
                    )
                  else
                    EndpointType.List(namespaced = false, plural, supportsWatch)
                case namespacedPattern(plural) =>
                  if (!containsNamespace)
                    EndpointType.Unsupported(
                      "Matches namespaced pattern but does not contain namespace parameter"
                    )
                  else
                    EndpointType.List(namespaced = true, plural, supportsWatch)
                case _ =>
                  EndpointType.Unsupported(s"Possibly subresource listing")
              }
            else
              EndpointType.Unsupported("Does not have 'continue' query parameter")
          else
            EndpointType.Unsupported("Does not have 'limit' query parameter")
        else
          EndpointType.Unsupported("Unsupported method for this action")

      case "watchlist" =>
        EndpointType.Unsupported("Deprecated action 'watchlist'")

      case "get" =>
        val clusterPattern =
          if (endpoint.group.isEmpty)
            s"""/api/${endpoint.version}/([a-z]+)/\\{name\\}""".r
          else
            s"""/apis/${endpoint.group}/${endpoint.version}/([a-z]+)/\\{name\\}""".r

        val namespacedPattern =
          if (endpoint.group.isEmpty)
            s"""/api/${endpoint.version}/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}""".r
          else
            s"""/apis/${endpoint.group}/${endpoint.version}/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}""".r

        if (endpoint.method == PathItem.HttpMethod.GET)
          if (containsName)
            endpoint.name match {
              case clusterPattern(plural) =>
                if (containsNamespace)
                  EndpointType.Unsupported(
                    "Matches cluster pattern but contains namespace parameter"
                  )
                else
                  EndpointType.Get(namespaced = false, plural)
              case namespacedPattern(plural) =>
                if (!containsNamespace)
                  EndpointType.Unsupported(
                    "Matches namespaced pattern but does not contain namespace parameter"
                  )
                else
                  EndpointType.Get(namespaced = true, plural)
              case _ =>
                EndpointType.Unsupported(s"Possibly subresource listing")
            }
          else
            EndpointType.Unsupported("Does not have 'name' parameter")
        else
          EndpointType.Unsupported("Unsupported method for this action")

      case "post" =>
        val containsDryRun = containsParameterDefinition(outer, inner, "dryRun")

        val clusterPattern =
          if (endpoint.group.isEmpty)
            s"""/api/${endpoint.version}/([a-z]+)""".r
          else
            s"""/apis/${endpoint.group}/${endpoint.version}/([a-z]+)""".r

        val namespacedPattern =
          if (endpoint.group.isEmpty)
            s"""/api/${endpoint.version}/namespaces/\\{namespace\\}/([a-z]+)""".r
          else
            s"""/apis/${endpoint.group}/${endpoint.version}/namespaces/\\{namespace\\}/([a-z]+)""".r

        if (endpoint.method == PathItem.HttpMethod.POST)
          if (containsDryRun)
            endpoint.name match {
              case clusterPattern(plural) =>
                if (containsNamespace)
                  EndpointType.Unsupported(
                    "Matches cluster pattern but contains namespace parameter"
                  )
                else
                  EndpointType.Post(namespaced = false, plural)
              case namespacedPattern(plural) =>
                if (!containsNamespace)
                  EndpointType.Unsupported(
                    "Matches namespaced pattern but does not contain namespace parameter"
                  )
                else
                  EndpointType.Post(namespaced = true, plural)
              case _ =>
                EndpointType.Unsupported(s"Possibly subresource listing")
            }
          else
            EndpointType.Unsupported("Does not contain 'dryRun' parameter")
        else
          EndpointType.Unsupported("Unsupported method for this action")

      case "put" =>
        val clusterPattern =
          if (endpoint.group.isEmpty)
            s"""/api/${endpoint.version}/([a-z]+)/\\{name\\}""".r
          else
            s"""/apis/${endpoint.group}/${endpoint.version}/([a-z]+)/\\{name\\}""".r

        val namespacedPattern =
          if (endpoint.group.isEmpty)
            s"""/api/${endpoint.version}/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}""".r
          else
            s"""/apis/${endpoint.group}/${endpoint.version}/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}""".r

        val clusterStatusPattern =
          if (endpoint.group.isEmpty)
            s"""/api/${endpoint.version}/([a-z]+)/\\{name\\}/status""".r
          else
            s"""/apis/${endpoint.group}/${endpoint.version}/([a-z]+)/\\{name\\}/status""".r

        val namespacedStatusPattern =
          if (endpoint.group.isEmpty)
            s"""/api/${endpoint.version}/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}/status""".r
          else
            s"""/apis/${endpoint.group}/${endpoint.version}/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}/status""".r

        if (endpoint.method == PathItem.HttpMethod.PUT)
          if (containsName)
            bodyType match {
              case Some(modelName) =>
                endpoint.name match {
                  case clusterPattern(plural) =>
                    if (containsNamespace)
                      EndpointType.Unsupported(
                        "Matches cluster pattern but contains namespace parameter"
                      )
                    else
                      EndpointType.Put(namespaced = false, plural, modelName)
                  case namespacedPattern(plural) =>
                    if (!containsNamespace)
                      EndpointType.Unsupported(
                        "Matches namespaced pattern but does not contain namespace parameter"
                      )
                    else
                      EndpointType.Put(namespaced = true, plural, modelName)
                  case clusterStatusPattern(plural) =>
                    if (containsNamespace)
                      EndpointType.Unsupported(
                        "Matches cluster pattern but contains namespace parameter"
                      )
                    else
                      EndpointType.PutStatus(namespaced = false, plural)
                  case namespacedStatusPattern(plural) =>
                    if (!containsNamespace)
                      EndpointType.Unsupported(
                        "Matches namespaced pattern but does not contain namespace parameter"
                      )
                    else
                      EndpointType.PutStatus(namespaced = true, plural)
                  case _ =>
                    EndpointType.Unsupported(s"Possibly subresource listing")
                }
              case None =>
                EndpointType.Unsupported("Does not have 'body' parameter")
            }
          else
            EndpointType.Unsupported("Does not have 'name' parameter")
        else
          EndpointType.Unsupported("Unsupported method for this action")

      case "delete" =>
        val clusterPattern =
          if (endpoint.group.isEmpty)
            s"""/api/${endpoint.version}/([a-z]+)/\\{name\\}""".r
          else
            s"""/apis/${endpoint.group}/${endpoint.version}/([a-z]+)/\\{name\\}""".r

        val namespacedPattern =
          if (endpoint.group.isEmpty)
            s"""/api/${endpoint.version}/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}""".r
          else
            s"""/apis/${endpoint.group}/${endpoint.version}/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}""".r

        if (endpoint.method == PathItem.HttpMethod.DELETE)
          if (containsName)
            endpoint.name match {
              case clusterPattern(plural) =>
                if (containsNamespace)
                  EndpointType.Unsupported(
                    "Matches cluster pattern but contains namespace parameter"
                  )
                else
                  EndpointType.Delete(namespaced = false, plural)
              case namespacedPattern(plural) =>
                if (!containsNamespace)
                  EndpointType.Unsupported(
                    "Matches namespaced pattern but does not contain namespace parameter"
                  )
                else
                  EndpointType.Delete(namespaced = true, plural)
              case _ =>
                EndpointType.Unsupported(s"Possibly subresource listing")
            }
          else
            EndpointType.Unsupported("Does not have 'name' parameter")
        else
          EndpointType.Unsupported("Unsupported method for this action")

      case _ =>
        EndpointType.Unsupported(s"Unsupported action: ${endpoint.action}")
    }
  }

  private def containsParameterDefinition(
    outerParameters: scala.List[Parameter],
    innerParameters: scala.List[Parameter],
    name: String
  ): Boolean =
    outerParameters.exists(
      _.getName == name
    ) || innerParameters.exists(
      _.getName == name
    )

}
