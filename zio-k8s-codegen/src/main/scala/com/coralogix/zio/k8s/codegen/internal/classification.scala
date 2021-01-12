package com.coralogix.zio.k8s.codegen.internal

sealed trait ClassifiedResource
case class SupportedResource(
  namespaced: Boolean,
  hasStatus: Boolean,
  group: String,
  kind: String,
  version: String,
  modelName: String,
  plural: String,
  modelReferences: Set[String]
) extends ClassifiedResource {
  def id: String = s"$group/$kind/$version"
}
case class UnsupportedResource(
  group: String,
  kind: String,
  version: String,
  actions: Set[IdentifiedAction],
  reason: String
) extends ClassifiedResource

object ClassifiedResource {
  def classifyActions(
    definitionMap: Map[String, IdentifiedSchema],
    identified: Iterable[IdentifiedAction]
  ): Set[SupportedResource] = {
    val groups = identified.groupBy(_.group)

    groups.flatMap {
      case (group, paths) =>
        paths.groupBy(_.kind).flatMap {
          case (kind, paths) =>
            paths.groupBy(_.version).flatMap {
              case (version, actions) =>
                val classification =
                  classifyResource(definitionMap, group, kind, version, actions.toSet)
                classification match {
                  case c: SupportedResource =>
                    Set(c)
                  case _: UnsupportedResource =>
                    Set.empty[SupportedResource]
                }
            }
        }
    }.toSet
  }

  private def classifyResource(
    definitions: Map[String, IdentifiedSchema],
    group: String,
    kind: String,
    version: String,
    actions: Set[IdentifiedAction]
  ): ClassifiedResource = {
    val endpoints = actions.map(action => EndpointType.detectEndpointType(action) -> action).toMap

    def classifyAs(namespaced: Boolean): Option[ClassifiedResource] =
      if (
        hasList(endpoints.keySet, namespaced, supportsWatch = true) &&
        hasPost(endpoints.keySet, namespaced) &&
        hasGet(endpoints.keySet, namespaced) &&
        hasPut(endpoints.keySet, namespaced) &&
        hasDelete(endpoints.keySet, namespaced)
      ) {
        val hasStatus = hasPutStatus(endpoints.keySet, namespaced)
        val refs = endpoints
          .filter {
            case (EndpointType.Unsupported(_), _) => false
            case _                                => true
          }
          .foldLeft(Set.empty[String]) {
            case (result, (_, action)) =>
              result union action.deepRefs(definitions, Set.empty)
          }

        val plural = endpoints.keys
          .collectFirst {
            case EndpointType.List(_, plural, _) => plural
          }
          .getOrElse("???")

        val modelName = endpoints.keys
          .collectFirst {
            case EndpointType.Put(_, _, modelName) => modelName
          }
          .getOrElse("???")

        Some(
          SupportedResource(
            namespaced,
            hasStatus,
            group,
            kind,
            version = version,
            modelName,
            plural,
            modelReferences = refs
          )
        )
      } else
        None

    classifyAs(true) orElse classifyAs(false) getOrElse UnsupportedResource(
      group,
      kind,
      version,
      actions,
      "Not implemented yet"
    )
  }

  private def hasList(
    endpoints: Set[EndpointType],
    namespaced: Boolean,
    supportsWatch: Boolean
  ): Boolean =
    endpoints
      .collect {
        case t: EndpointType.List => t
      }
      .exists(t => t.namespaced == namespaced && t.supportsWatch == supportsWatch)
  private def hasGet(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect {
        case t: EndpointType.Get => t
      }
      .exists(t => t.namespaced == namespaced)
  private def hasPost(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect {
        case t: EndpointType.Post => t
      }
      .exists(t => t.namespaced == namespaced)
  private def hasPut(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect {
        case t: EndpointType.Put => t
      }
      .exists(t => t.namespaced == namespaced)
  private def hasPutStatus(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect {
        case t: EndpointType.PutStatus => t
      }
      .exists(t => t.namespaced == namespaced)
  private def hasDelete(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect {
        case t: EndpointType.Delete => t
      }
      .exists(t => t.namespaced == namespaced)

}
