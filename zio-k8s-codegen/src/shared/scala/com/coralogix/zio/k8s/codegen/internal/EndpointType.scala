package com.coralogix.zio.k8s.codegen.internal

import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.parameters.Parameter

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.matching._

sealed trait EndpointType
object EndpointType {
  case class List(namespaced: Boolean, detectedPlural: String, supportsWatch: Boolean)
      extends EndpointType
  case class Get(namespaced: Boolean, detectedPlural: String) extends EndpointType
  case class Post(namespaced: Boolean, detectedPlural: String) extends EndpointType
  case class Put(namespaced: Boolean, detectedPlural: String, modelName: String)
      extends EndpointType
  case class Patch(namespaced: Boolean, detectedPlural: String, modelName: String)
      extends EndpointType
  case class Delete(namespaced: Boolean, detectedPlural: String, resultTypeRef: String)
      extends EndpointType
  case class DeleteMany(namespaced: Boolean, detectedPlural: String) extends EndpointType

  trait SubresourceEndpoint extends EndpointType {
    val subresourceName: String
    val rootPath: String
  }

  case class GetSubresource(
    subresourceName: String,
    namespaced: Boolean,
    detectedPlural: String,
    rootPath: String
  ) extends SubresourceEndpoint
  case class PutSubresource(
    subresourceName: String,
    namespaced: Boolean,
    modelName: String,
    detectedPlural: String,
    rootPath: String
  ) extends SubresourceEndpoint
  case class PatchSubresource(
    subresourceName: String,
    namespaced: Boolean,
    detectedPlural: String,
    rootPath: String
  ) extends SubresourceEndpoint
  case class PostSubresource(
    subresourceName: String,
    namespaced: Boolean,
    modelName: String,
    detectedPlural: String,
    rootPath: String
  ) extends SubresourceEndpoint

  case class Unsupported(reason: String) extends EndpointType

  def detectEndpointType(endpoint: IdentifiedAction): EndpointType = {
    val guards = Guards(endpoint)

    detectSubresourceEndpoints(endpoint, guards) match {
      case (EndpointType.Unsupported("fallback")) =>
        detectResourceEndpoints(endpoint, guards)
      case result: EndpointType                   =>
        result
    }
  }

  private def detectSubresourceEndpoints(
    endpoint: IdentifiedAction,
    guards: Guards
  ): EndpointType =
    endpoint.action match {
      case "get" =>
        val clusterStatusPattern = ClusterPatterns.getSubresource
        val namespacedStatusPattern = NamespacedPatterns.getSubresource

        guards.mustHaveMethod(PathItem.HttpMethod.GET) {
          guards.mustHaveParameters("name") {
            endpoint.name match {
              case clusterStatusPattern(rootPath, _, _, _, _, plural, subresource)    =>
                guards.mustHaveNotHaveParameter("namespace") {
                  EndpointType.GetSubresource(subresource, namespaced = false, plural, rootPath)
                }
              case namespacedStatusPattern(rootPath, _, _, _, _, plural, subresource) =>
                guards.mustHaveParameters("namespace") {
                  EndpointType.GetSubresource(subresource, namespaced = true, plural, rootPath)
                }
              case _                                                                  =>
                EndpointType.Unsupported("fallback")
            }
          }
        }

      case "post" =>
        val clusterStatusPattern = ClusterPatterns.putSubresource
        val namespacedStatusPattern = NamespacedPatterns.putSubresource

        guards.mustHaveMethod(PathItem.HttpMethod.POST) {
          guards.mustHaveParameters("dryRun") {
            guards.mustHaveBody { modelName =>
              endpoint.name match {
                case clusterStatusPattern(rootPath, _, _, _, _, plural, subresource)    =>
                  guards.mustHaveNotHaveParameter("namespace") {
                    EndpointType.PostSubresource(
                      subresource,
                      namespaced = false,
                      modelName,
                      plural,
                      rootPath
                    )
                  }
                case namespacedStatusPattern(rootPath, _, _, _, _, plural, subresource) =>
                  guards.mustHaveParameters("namespace") {
                    EndpointType.PostSubresource(
                      subresource,
                      namespaced = true,
                      modelName,
                      plural,
                      rootPath
                    )
                  }
                case _                                                                  =>
                  EndpointType.Unsupported("fallback")
              }
            }
          }
        }

      case "put" =>
        val clusterStatusPattern = ClusterPatterns.putSubresource
        val namespacedStatusPattern = NamespacedPatterns.putSubresource

        guards.mustHaveMethod(PathItem.HttpMethod.PUT) {
          guards.mustHaveParameters("name") {
            guards.mustHaveParameters("dryRun") {
              guards.mustHaveBody { modelName =>
                endpoint.name match {
                  case clusterStatusPattern(rootPath, _, _, _, _, plural, subresource)    =>
                    guards.mustHaveNotHaveParameter("namespace") {
                      EndpointType.PutSubresource(
                        subresource,
                        namespaced = false,
                        modelName,
                        plural,
                        rootPath
                      )
                    }
                  case namespacedStatusPattern(rootPath, _, _, _, _, plural, subresource) =>
                    guards.mustHaveParameters("namespace") {
                      EndpointType.PutSubresource(
                        subresource,
                        namespaced = true,
                        modelName,
                        plural,
                        rootPath
                      )
                    }
                  case _                                                                  =>
                    EndpointType.Unsupported("fallback")
                }
              }
            }
          }
        }

      case "patch" =>
        val clusterStatusPattern = ClusterPatterns.patchSubresource
        val namespacedStatusPattern = NamespacedPatterns.patchSubresource

        guards.mustHaveMethod(PathItem.HttpMethod.PATCH) {
          guards.mustHaveParameters("name") {
            guards.mustHaveBody { _ =>
              endpoint.name match {
                case clusterStatusPattern(rootPath, _, _, _, _, plural, subresource)    =>
                  guards.mustHaveNotHaveParameter("namespace") {
                    EndpointType.PatchSubresource(subresource, namespaced = false, plural, rootPath)
                  }
                case namespacedStatusPattern(rootPath, _, _, _, _, plural, subresource) =>
                  guards.mustHaveParameters("namespace") {
                    EndpointType.PatchSubresource(subresource, namespaced = true, plural, rootPath)
                  }
                case _                                                                  =>
                  EndpointType.Unsupported("fallback")
              }
            }
          }
        }

      case _ => EndpointType.Unsupported("fallback")
    }

  private def detectResourceEndpoints(
    endpoint: IdentifiedAction,
    guards: Guards
  ): EndpointType =
    endpoint.action match {
      case "list" =>
        val supportsWatch = guards.haveOptionalParameters("watch")

        val clusterPattern = ClusterPatterns.list
        val namespacedPattern = NamespacedPatterns.list

        guards.mustHaveMethod(PathItem.HttpMethod.GET) {
          guards.mustHaveParameters(
            "limit",
            "continue",
            "resourceVersion",
            "resourceVersionMatch",
            "fieldSelector",
            "labelSelector"
          ) {
            endpoint.name match {
              case clusterPattern(_, _, group, version, plural)    =>
                guards.mustHaveSame(group, version) {
                  guards.mustHaveNotHaveParameter("namespace") {
                    EndpointType.List(namespaced = false, plural, supportsWatch)
                  }
                }
              case namespacedPattern(_, _, group, version, plural) =>
                guards.mustHaveSame(group, version) {
                  guards.mustHaveParameters("namespace") {
                    EndpointType.List(namespaced = true, plural, supportsWatch)
                  }
                }
              case _                                               =>
                EndpointType.Unsupported(s"Possibly subresource listing")
            }
          }
        }

      case "get" =>
        val clusterPattern = ClusterPatterns.get
        val namespacedPattern = NamespacedPatterns.get

        guards.mustHaveMethod(PathItem.HttpMethod.GET) {
          guards.mustHaveParameters("name") {
            endpoint.name match {
              case clusterPattern(_, _, group, version, plural)    =>
                guards.mustHaveSame(group, version) {
                  guards.mustHaveNotHaveParameter("namespace") {
                    EndpointType.Get(namespaced = false, plural)
                  }
                }
              case namespacedPattern(_, _, group, version, plural) =>
                guards.mustHaveSame(group, version) {
                  guards.mustHaveParameters("namespace") {
                    EndpointType.Get(namespaced = true, plural)
                  }
                }
              case _                                               =>
                EndpointType.Unsupported("Possibly subresource get")
            }
          }
        }

      case "post" =>
        val clusterPattern = ClusterPatterns.post
        val namespacedPattern = NamespacedPatterns.post

        guards.mustHaveMethod(PathItem.HttpMethod.POST) {
          guards.mustHaveParameters("dryRun") {
            endpoint.name match {
              case clusterPattern(_, _, group, version, plural)    =>
                guards.mustHaveSame(group, version) {
                  guards.mustHaveNotHaveParameter("namespace") {
                    EndpointType.Post(namespaced = false, plural)
                  }
                }
              case namespacedPattern(_, _, group, version, plural) =>
                guards.mustHaveSame(group, version) {
                  guards.mustHaveParameters("namespace") {
                    EndpointType.Post(namespaced = true, plural)
                  }
                }
              case _                                               =>
                EndpointType.Unsupported(s"Possibly subresource post")
            }
          }
        }

      case "put" =>
        val clusterPattern = ClusterPatterns.put
        val namespacedPattern = NamespacedPatterns.put

        guards.mustHaveMethod(PathItem.HttpMethod.PUT) {
          guards.mustHaveParameters("name", "dryRun") {
            guards.mustHaveBody { modelName =>
              endpoint.name match {
                case clusterPattern(_, _, group, version, plural)    =>
                  guards.mustHaveSame(group, version) {
                    guards.mustHaveNotHaveParameter("namespace") {
                      EndpointType.Put(namespaced = false, plural, modelName)
                    }
                  }
                case namespacedPattern(_, _, group, version, plural) =>
                  guards.mustHaveSame(group, version) {
                    guards.mustHaveParameters("namespace") {
                      EndpointType.Put(namespaced = true, plural, modelName)
                    }
                  }
                case _                                               =>
                  EndpointType.Unsupported(s"Possibly subresource put")
              }
            }
          }
        }

      case "delete"           =>
        val clusterPattern = ClusterPatterns.delete
        val namespacedPattern = NamespacedPatterns.delete

        guards.mustHaveMethod(PathItem.HttpMethod.DELETE) {
          guards.mustHaveParameters("name", "dryRun", "gracePeriodSeconds", "propagationPolicy") {
            endpoint.name match {
              case clusterPattern(_, _, group, version, plural)    =>
                guards.mustHaveSame(group, version) {
                  guards.mustHaveNotHaveParameter("namespace") {
                    guards.mustHaveResponseTypeRef { responseTypeRef =>
                      EndpointType.Delete(namespaced = false, plural, responseTypeRef)
                    }
                  }
                }
              case namespacedPattern(_, _, group, version, plural) =>
                guards.mustHaveSame(group, version) {
                  guards.mustHaveParameters("namespace") {
                    guards.mustHaveResponseTypeRef { responseTypeRef =>
                      EndpointType.Delete(namespaced = true, plural, responseTypeRef)
                    }
                  }
                }
              case _                                               =>
                EndpointType.Unsupported(s"Possibly subresource delete")
            }
          }
        }
      case "deletecollection" =>
        val clusterPattern = ClusterPatterns.deleteMany
        val namespacedPattern = NamespacedPatterns.deleteMany

        guards.mustHaveMethod(PathItem.HttpMethod.DELETE) {
          guards.mustHaveParameters(
            "dryRun",
            "gracePeriodSeconds",
            "propagationPolicy",
            "fieldSelector",
            "labelSelector"
          ) {
            endpoint.name match {
              case clusterPattern(_, _, group, version, plural)    =>
                guards.mustHaveSame(group, version) {
                  guards.mustHaveNotHaveParameter("namespace") {
                    EndpointType.DeleteMany(namespaced = false, plural)
                  }
                }
              case namespacedPattern(_, _, group, version, plural) =>
                guards.mustHaveSame(group, version) {
                  guards.mustHaveParameters("namespace") {
                    EndpointType.DeleteMany(namespaced = true, plural)
                  }
                }
              case _                                               =>
                EndpointType.Unsupported(s"Possibly subresource deletecollection")
            }
          }
        }

      case "patch" =>
        val clusterPattern = ClusterPatterns.patch
        val namespacedPattern = NamespacedPatterns.patch

        guards.mustHaveMethod(PathItem.HttpMethod.PATCH) {
          guards.mustHaveParameters("name") {
            guards.mustHaveBody { modelName =>
              endpoint.name match {
                case clusterPattern(_, _, group, version, plural)    =>
                  guards.mustHaveSame(group, version) {
                    guards.mustHaveNotHaveParameter("namespace") {
                      EndpointType.Patch(namespaced = false, plural, modelName)
                    }
                  }
                case namespacedPattern(_, _, group, version, plural) =>
                  guards.mustHaveSame(group, version) {
                    guards.mustHaveParameters("namespace") {
                      EndpointType.Patch(namespaced = true, plural, modelName)
                    }
                  }
                case _                                               =>
                  EndpointType.Unsupported(s"Possibly subresource patch")
              }
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

  final class Guards(endpoint: IdentifiedAction) {
    private val outer: scala.List[Parameter] = endpoint.outerParameters
    private val inner: scala.List[Parameter] = endpoint.innerParameters

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

    def mustHaveSame(group: String, version: String)(f: => EndpointType): EndpointType =
      if (endpoint.gvk.group == Option(group).getOrElse("") && endpoint.gvk.version == version)
        f
      else
        EndpointType.Unsupported("Group/version mismatch")

    def mustHaveResponseTypeRef(f: String => EndpointType): EndpointType =
      endpoint.responseTypeRef match {
        case Some(ref) => f(ref)
        case None      => EndpointType.Unsupported("Does not have a response type")
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

  trait Patterns {
    val list: Regex
    val get: Regex
    val post: Regex
    val put: Regex
    val patch: Regex
    val delete: Regex
    val deleteMany: Regex

    val getSubresource: Regex
    val putSubresource: Regex
    val patchSubresource: Regex
    val postSubresource: Regex
  }

  final object ClusterPatterns extends Patterns {
    val list: Regex = s"""/api(/|(s/([a-z0-9.]+))/)([a-z0-9]+)/([a-z]+)""".r
    val get: Regex = s"""/api(/|(s/([a-z0-9.]+))/)([a-z0-9]+)/([a-z]+)/\\{name\\}""".r
    val post: Regex = list
    val patch: Regex = get
    val put: Regex = get
    val delete: Regex = get
    val deleteMany: Regex = list

    val getSubresource: Regex =
      s"""(/api(/|(s/([a-z0-9.]+))/)([a-z0-9]+)/([a-z]+)/\\{name\\})/([a-z]+)""".r
    val putSubresource: Regex = getSubresource
    val patchSubresource: Regex = getSubresource
    val postSubresource: Regex = getSubresource
  }

  final object NamespacedPatterns extends Patterns {
    val list: Regex =
      s"""/api(/|(s/([a-z0-9.]+))/)([a-z0-9]+)/namespaces/\\{namespace\\}/([a-z]+)""".r
    val get: Regex =
      s"""/api(/|(s/([a-z0-9.]+))/)([a-z0-9]+)/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\}""".r
    val post: Regex = list
    val patch: Regex = get
    val put: Regex = get
    val delete: Regex = get
    val deleteMany: Regex = list

    val getSubresource: Regex =
      s"""(/api(/|(s/([a-z0-9.]+))/)([a-z0-9]+)/namespaces/\\{namespace\\}/([a-z]+)/\\{name\\})/([a-z]+)""".r
    val putSubresource: Regex = getSubresource
    val patchSubresource: Regex = getSubresource
    val postSubresource: Regex = getSubresource
  }
}
