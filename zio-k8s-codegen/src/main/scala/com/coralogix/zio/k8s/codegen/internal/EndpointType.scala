package com.coralogix.zio.k8s.codegen.internal

import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.parameters.Parameter

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.matching.Regex

sealed trait EndpointType
object EndpointType {
  case class List(namespaced: Boolean, detectedPlural: String, supportsWatch: Boolean)
      extends EndpointType
  case class Get(namespaced: Boolean, detectedPlural: String) extends EndpointType
  case class Post(namespaced: Boolean, detectedPlural: String) extends EndpointType
  case class Put(namespaced: Boolean, detectedPlural: String, modelName: String)
      extends EndpointType
  case class PutStatus(namespaced: Boolean, detectedPlural: String) extends EndpointType
  case class GetStatus(namespaced: Boolean, detectedPlural: String) extends EndpointType
  case class Delete(namespaced: Boolean, detectedPlural: String) extends EndpointType

  case class Unsupported(reason: String) extends EndpointType

  private def detectSubresourceEndpoints(
    endpoint: IdentifiedAction,
    clusterPatterns: Patterns,
    namespacedPatterns: Patterns,
    guards: Guards
  ): EndpointType =
    endpoint.action match {
      case "get" =>
        val clusterStatusPattern = clusterPatterns.getStatus
        val namespacedStatusPattern = namespacedPatterns.getStatus

        // TODO: match any subresource pattern
        guards.mustHaveMethod(PathItem.HttpMethod.GET) {
          guards.mustHaveParameters("name") {
            endpoint.name match {
              case clusterStatusPattern(plural)    =>
                guards.mustHaveNotHaveParameter("namespace") {
                  EndpointType.GetStatus(namespaced = false, plural)
                }
              case namespacedStatusPattern(plural) =>
                guards.mustHaveParameters("namespace") {
                  EndpointType.GetStatus(namespaced = true, plural)
                }
              case _                               =>
                EndpointType.Unsupported("fallback")
            }
          }
        }

      case "post" =>
        // TODO: match any subresources
        EndpointType.Unsupported("fallback")

      case "put" =>
        val clusterStatusPattern = clusterPatterns.putStatus
        val namespacedStatusPattern = namespacedPatterns.putStatus
        // TODO: match any subresources

        guards.mustHaveMethod(PathItem.HttpMethod.PUT) {
          guards.mustHaveParameters("name") {
            guards.mustHaveBody { _ =>
              endpoint.name match {
                case clusterStatusPattern(plural)    =>
                  guards.mustHaveNotHaveParameter("namespace") {
                    EndpointType.PutStatus(namespaced = false, plural)
                  }
                case namespacedStatusPattern(plural) =>
                  guards.mustHaveParameters("namespace") {
                    EndpointType.PutStatus(namespaced = true, plural)
                  }
                case _                               =>
                  EndpointType.Unsupported("fallback")
              }
            }
          }
        }

      case _ => EndpointType.Unsupported("fallback")
    }

  private def detectResourceEndpoints(
    endpoint: IdentifiedAction,
    clusterPatterns: Patterns,
    namespacedPatterns: Patterns,
    guards: Guards
  ): EndpointType =
    endpoint.action match {
      case "list" =>
        val supportsWatch = guards.haveOptionalParameters("watch", "resourceVersion")

        val clusterPattern = clusterPatterns.list
        val namespacedPattern = namespacedPatterns.list

        guards.mustHaveMethod(PathItem.HttpMethod.GET) {
          guards.mustHaveParameters("limit", "continue") {
            endpoint.name match {
              case clusterPattern(plural)    =>
                guards.mustHaveNotHaveParameter("namespace") {
                  EndpointType.List(namespaced = false, plural, supportsWatch)
                }
              case namespacedPattern(plural) =>
                guards.mustHaveParameters("namespace") {
                  EndpointType.List(namespaced = true, plural, supportsWatch)
                }
              case _                         =>
                EndpointType.Unsupported(s"Possibly subresource listing")
            }
          }
        }

      case "get" =>
        val clusterPattern = clusterPatterns.get
        val namespacedPattern = namespacedPatterns.get

        guards.mustHaveMethod(PathItem.HttpMethod.GET) {
          guards.mustHaveParameters("name") {
            endpoint.name match {
              case clusterPattern(plural)    =>
                guards.mustHaveNotHaveParameter("namespace") {
                  EndpointType.Get(namespaced = false, plural)
                }
              case namespacedPattern(plural) =>
                guards.mustHaveParameters("namespace") {
                  EndpointType.Get(namespaced = true, plural)
                }
              case _                         =>
                EndpointType.Unsupported("Possibly subresource listing")
            }
          }
        }

      case "post" =>
        val clusterPattern = clusterPatterns.post
        val namespacedPattern = namespacedPatterns.post

        // TODO: match any subresources

        guards.mustHaveMethod(PathItem.HttpMethod.POST) {
          guards.mustHaveParameters("dryRun") {
            endpoint.name match {
              case clusterPattern(plural)    =>
                guards.mustHaveNotHaveParameter("namespace") {
                  EndpointType.Post(namespaced = false, plural)
                }
              case namespacedPattern(plural) =>
                guards.mustHaveParameters("namespace") {
                  EndpointType.Post(namespaced = true, plural)
                }
              case _                         =>
                EndpointType.Unsupported(s"Possibly subresource listing")
            }
          }
        }

      case "put" =>
        val clusterPattern = clusterPatterns.put
        val namespacedPattern = namespacedPatterns.put

        guards.mustHaveMethod(PathItem.HttpMethod.PUT) {
          guards.mustHaveParameters("name") {
            guards.mustHaveBody { modelName =>
              endpoint.name match {
                case clusterPattern(plural)    =>
                  guards.mustHaveNotHaveParameter("namespace") {
                    EndpointType.Put(namespaced = false, plural, modelName)
                  }
                case namespacedPattern(plural) =>
                  guards.mustHaveParameters("namespace") {
                    EndpointType.Put(namespaced = true, plural, modelName)
                  }
                case _                         =>
                  EndpointType.Unsupported(s"Possibly subresource listing")
              }
            }
          }
        }

      case "delete" =>
        val clusterPattern = clusterPatterns.delete
        val namespacedPattern = namespacedPatterns.delete

        guards.mustHaveMethod(PathItem.HttpMethod.DELETE) {
          guards.mustHaveParameters("name") {
            endpoint.name match {
              case clusterPattern(plural)    =>
                guards.mustHaveNotHaveParameter("namespace") {
                  EndpointType.Delete(namespaced = false, plural)
                }
              case namespacedPattern(plural) =>
                guards.mustHaveParameters("namespace") {
                  EndpointType.Delete(namespaced = true, plural)
                }
              case _                         =>
                EndpointType.Unsupported(s"Possibly subresource listing")
            }
          }
        }

      case "watch"     =>
        EndpointType.Unsupported("Deprecated action 'watchlist'")
      case "watchlist" =>
        EndpointType.Unsupported("Deprecated action 'watchlist'")
      case _           =>
        EndpointType.Unsupported(s"Unsupported action: ${endpoint.action}")
    }

  def detectEndpointType(endpoint: IdentifiedAction): EndpointType = {
    val clusterPatterns = Patterns.cluster(endpoint)
    val namespacedPatterns = Patterns.namespaced(endpoint)
    val guards = Guards(endpoint)

    detectSubresourceEndpoints(endpoint, clusterPatterns, namespacedPatterns, guards) match {
      case _: EndpointType.Unsupported =>
        detectResourceEndpoints(endpoint, clusterPatterns, namespacedPatterns, guards)
      case result: EndpointType        =>
        result
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

  trait Patterns {
    val list: Regex
    val get: Regex
    val post: Regex
    val put: Regex
    val delete: Regex

    val getStatus: Regex
    val putStatus: Regex
  }

  final class Guards(endpoint: IdentifiedAction) {
    private val outer: scala.List[Parameter] = endpoint.outerParameters
    private val inner: scala.List[Parameter] =
      Option(endpoint.op.getParameters).map(_.asScala.toList).getOrElse(scala.List.empty)

    def mustHaveMethod(method: PathItem.HttpMethod)(f: => EndpointType): EndpointType =
      if (endpoint.method == method) {
        f
      } else {
        EndpointType.Unsupported("Unsupported method for this action")
      }

    @tailrec
    def mustHaveParameters(names: String*)(f: => EndpointType): EndpointType =
      names match {
        case Nil          => f
        case name +: rest =>
          if (containsParameterDefinition(outer, inner, name))
            mustHaveParameters(rest: _*)(f)
          else
            EndpointType.Unsupported(s"Does not have '$name' parameter")
      }

    def haveOptionalParameters(names: String*): Boolean =
      names.forall(containsParameterDefinition(outer, inner, _))

    def mustHaveNotHaveParameter(name: String)(f: => EndpointType): EndpointType =
      if (containsParameterDefinition(outer, inner, name))
        EndpointType.Unsupported(s"Should not have '$name' parameter")
      else
        f

    def mustHaveBody(f: String => EndpointType): EndpointType =
      getBodyType match {
        case Some(modelName) => f(modelName)
        case None            => EndpointType.Unsupported("Does not have 'body' parameter")
      }

    private def getBodyType =
      for {
        requestBody  <- Option(endpoint.op.getRequestBody)
        content      <- Option(requestBody.getContent)
        firstContent <- content.asScala.values.headOption
        schema       <- Option(firstContent.getSchema)
        ref          <- Option(schema.get$ref())
      } yield ref.drop("#/components/schemas/".length)

  }

  object Guards {
    def apply(endpoint: IdentifiedAction): Guards = new Guards(endpoint)
  }

  object Patterns {
    def cluster(endpoint: IdentifiedAction): Patterns =
      if (endpoint.group.isEmpty)
        new CoreClusterPatterns(endpoint.version)
      else
        new ExtendedClusterPatterns(endpoint.group, endpoint.version)
    def namespaced(endpoint: IdentifiedAction): Patterns =
      if (endpoint.group.isEmpty)
        new CoreNamespacedPatterns(endpoint.version)
      else
        new ExtendedNamespacedPatterns(endpoint.group, endpoint.version)
  }

  final class CoreClusterPatterns(version: String) extends Patterns {
    val list: Regex = s"""/api/$version/([a-z]+)""".r
    val get: Regex = s"""/api/$version/([a-z]+)/\\{name\\}""".r
    val post: Regex = list
    val put: Regex = get
    val delete: Regex = get

    val getStatus: Regex = s"""/api/$version/([a-z]+)/\\{name\\}/status""".r
    val putStatus: Regex = getStatus
  }

  final class ExtendedClusterPatterns(group: String, version: String) extends Patterns {
    val list: Regex = s"""/apis/$group/$version/([a-z]+)""".r
    val get: Regex = s"""/apis/$group/$version/([a-z]+)/\\{name\\}""".r
    val post: Regex = list
    val put: Regex = get
    val delete: Regex = get

    val getStatus: Regex = s"""/apis/$group/$version/([a-z]+)/\\{name\\}/status""".r
    val putStatus: Regex = getStatus

  }

  final class CoreNamespacedPatterns(version: String) extends Patterns {
    val list: Regex = s"""/api/$version/namespaces/\\{namespace\\}/([a-z]+)""".r
    val get: Regex = s"""/api/$version/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}""".r
    val post: Regex = list
    val put: Regex = get
    val delete: Regex = get

    val getStatus: Regex =
      s"""/api/$version/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}/status""".r
    val putStatus: Regex = getStatus
  }

  final class ExtendedNamespacedPatterns(group: String, version: String) extends Patterns {
    val list: Regex = s"""/apis/$group/$version/namespaces/\\{namespace\\}/([a-z]+)""".r
    val get: Regex = s"""/apis/$group/$version/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}""".r
    val post: Regex = list
    val put: Regex = get
    val delete: Regex = get

    val getStatus: Regex =
      s"""/apis/$group/$version/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}/status""".r
    val putStatus: Regex = getStatus
  }
}
