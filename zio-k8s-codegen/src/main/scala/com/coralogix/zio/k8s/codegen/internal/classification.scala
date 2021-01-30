package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.Whitelist.IssueReference
import zio.Task

sealed trait ClassifiedResource {
  val unsupportedEndpoints: Map[IdentifiedAction, EndpointType.Unsupported]
}
case class SupportedResource(
  namespaced: Boolean,
  hasStatus: Boolean,
  group: String,
  kind: String,
  version: String,
  modelName: String,
  plural: String,
  modelReferences: Set[String],
  actions: Set[IdentifiedAction],
  unsupportedEndpoints: Map[IdentifiedAction, EndpointType.Unsupported]
) extends ClassifiedResource {
  def id: String = s"$group/$version/$kind"

  def toUnsupported(reason: String): UnsupportedResource =
    UnsupportedResource(
      group,
      kind,
      version,
      actions,
      reason,
      unsupportedEndpoints
    )
}
case class UnsupportedResource(
  group: String,
  kind: String,
  version: String,
  actions: Set[IdentifiedAction],
  reason: String,
  unsupportedEndpoints: Map[IdentifiedAction, EndpointType.Unsupported]
) extends ClassifiedResource {

  def isGVK(group: String, version: String, kind: String): Boolean =
    this.group == group && this.version == version && this.kind == kind

  def describe: String = {
    val supportedActions = actions diff unsupportedEndpoints.keySet
    val supportedDescs = supportedActions.map(action => s"  - ${action.describe}").mkString("\n")
    val unsupportedDescs = unsupportedEndpoints
      .map { case (action, reason) => s"  - ${action.describe} - ${reason.reason}" }
      .mkString("\n")
    s"($group/$version/$kind) $reason, actions:\n$supportedDescs\n$unsupportedDescs"
  }
}

object ClassifiedResource {
  def classifyActions(
    logger: sbt.Logger,
    definitionMap: Map[String, IdentifiedSchema],
    identified: Iterable[IdentifiedAction]
  ): Task[Set[SupportedResource]] = {
    val groups = identified.groupBy(_.group)

    val all = groups.map { case (group, paths) =>
      paths.groupBy(_.kind).map { case (kind, paths) =>
        paths.groupBy(_.version).map { case (version, actions) =>
          val classification =
            classifyResource(logger, definitionMap, group, kind, version, actions.toSet)

          classification match {
            case supported: SupportedResource     =>
              val endpointWhitelist = classification.unsupportedEndpoints.map { case (action, _) =>
                Whitelist.isWhitelistedAction(action)
              }.toSet
              val hasUnexpectedUnsupportedEndpoints = endpointWhitelist.contains(None)
              val whitelistedIssues = endpointWhitelist.collect { case Some(issueRef) => issueRef }

              if (hasUnexpectedUnsupportedEndpoints) {
                val unsupported = supported.toUnsupported("Unsupported non-whitelisted actions")

                logger.error(s"Unsupported resource action found: ${unsupported.describe}")

                List[(Set[ClassifiedResource], Set[IssueReference])](
                  (Set(unsupported), whitelistedIssues)
                )
              } else {
                List[(Set[ClassifiedResource], Set[IssueReference])](
                  (Set(supported), whitelistedIssues)
                )
              }
            case unsupported: UnsupportedResource =>
              Whitelist.isWhitelisted(unsupported) match {
                case Some(issueRef) =>
                  List[(Set[ClassifiedResource], Set[IssueReference])](
                    (
                      Set.empty[ClassifiedResource],
                      Set(issueRef)
                    )
                  )
                case None           =>
                  logger.error(s"Unsupported resource action found: ${unsupported.describe}")
                  List[(Set[ClassifiedResource], Set[IssueReference])](
                    (Set(unsupported), Set.empty)
                  )
              }
          }
        }
      }
    }.toList.flatten.flatten.flatten

    val allResources = all.flatMap(_._1).toSet
    val allIssues = all.flatMap(_._2).toSet

    val hadUnsupported = allResources.exists {
      case _: UnsupportedResource => true
      case _                      => false
    }

    for {
      _      <- printIssues(logger, allIssues)
      result <- if (hadUnsupported) {
                  Task.fail(
                    new sbt.MessageOnlyException(
                      "Unknown, non-whitelisted resource actions found. See the code generation log."
                    )
                  )
                } else {
                  Task.succeed(allResources.collect { case supported: SupportedResource =>
                    supported
                  })
                }
    } yield result
  }

  private def printIssues(logger: sbt.Logger, issues: Set[IssueReference]) =
    for {
      _ <- Task.effect(logger.info(s"Issues for currently unsupported resources/actions:"))
      _ <- Task.foreach_(issues) { issue =>
             Task.effect(logger.info(s" - ${issue.url}"))
           }
    } yield ()

  private def classifyResource(
    logger: sbt.Logger,
    definitions: Map[String, IdentifiedSchema],
    group: String,
    kind: String,
    version: String,
    actions: Set[IdentifiedAction]
  ): ClassifiedResource = {
    val endpoints = actions.map(action => EndpointType.detectEndpointType(action) -> action).toMap
    val unsupportedEndpoints = actions
      .map(action => action -> EndpointType.detectEndpointType(action))
      .collect { case (action, t @ EndpointType.Unsupported(_)) => (action, t) }
      .toMap

    def classifyAs(namespaced: Boolean): Option[ClassifiedResource] =
      if (
        hasList(endpoints.keySet, namespaced, supportsWatch = true) &&
        hasPost(endpoints.keySet, namespaced) &&
        hasGet(endpoints.keySet, namespaced) &&
        hasPut(endpoints.keySet, namespaced) &&
        hasDelete(endpoints.keySet, namespaced)
      ) {
        val hasStatus =
          hasGetStatus(endpoints.keySet, namespaced) && hasPutStatus(endpoints.keySet, namespaced)
        val refs = endpoints
          .filter {
            case (EndpointType.Unsupported(_), _) => false
            case _                                => true
          }
          .foldLeft(Set.empty[String]) { case (result, (_, action)) =>
            result union action.deepRefs(logger, definitions, Set.empty)
          }

        val pluralOpt = endpoints.keys
          .collectFirst { case EndpointType.List(_, plural, _) =>
            plural
          }

        val modelNameOpt = endpoints.keys
          .collectFirst { case EndpointType.Put(_, _, modelName) =>
            modelName
          }

        for {
          plural    <- pluralOpt
          modelName <- modelNameOpt
        } yield SupportedResource(
          namespaced,
          hasStatus,
          group,
          kind,
          version = version,
          modelName,
          plural,
          modelReferences = refs,
          actions,
          unsupportedEndpoints
        )
      } else
        None

    classifyAs(true) orElse classifyAs(false) getOrElse UnsupportedResource(
      group,
      kind,
      version,
      actions,
      "Not implemented yet",
      unsupportedEndpoints
    )
  }

  private def hasList(
    endpoints: Set[EndpointType],
    namespaced: Boolean,
    supportsWatch: Boolean
  ): Boolean =
    endpoints
      .collect { case t: EndpointType.List =>
        t
      }
      .exists(t => t.namespaced == namespaced && t.supportsWatch == supportsWatch)
  private def hasGet(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect { case t: EndpointType.Get =>
        t
      }
      .exists(t => t.namespaced == namespaced)
  private def hasPost(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect { case t: EndpointType.Post =>
        t
      }
      .exists(t => t.namespaced == namespaced)
  private def hasPut(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect { case t: EndpointType.Put =>
        t
      }
      .exists(t => t.namespaced == namespaced)
  private def hasPutStatus(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect { case t: EndpointType.PutStatus =>
        t
      }
      .exists(t => t.namespaced == namespaced)
  private def hasGetStatus(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect { case t: EndpointType.GetStatus =>
        t
      }
      .exists(t => t.namespaced == namespaced)
  private def hasDelete(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect { case t: EndpointType.Delete =>
        t
      }
      .exists(t => t.namespaced == namespaced)

}
