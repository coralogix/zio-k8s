package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.Conversions.splitName
import com.coralogix.zio.k8s.codegen.internal.EndpointType.SubresourceEndpoint
import com.coralogix.zio.k8s.codegen.internal.Whitelist.IssueReference
import org.atteo.evo.inflector.English
import zio.Task

sealed trait ClassifiedResource {
  val unsupportedEndpoints: Set[IdentifiedAction]
}
case class SupportedResource(
  namespaced: Boolean,
  hasStatus: Boolean,
  gvk: GroupVersionKind,
  modelName: String,
  plural: String,
  modelReferences: Set[String],
  actions: Set[IdentifiedAction],
  unsupportedEndpoints: Set[IdentifiedAction]
) extends ClassifiedResource {
  def id: String = gvk.toString

  def toUnsupported(reason: String): UnsupportedResource =
    UnsupportedResource(
      gvk,
      actions,
      reason,
      unsupportedEndpoints
    )

  def supportsDeleteMany: Boolean =
    actions
      .map(_.endpointType)
      .collectFirst { case EndpointType.DeleteMany(_, _) =>
        true
      }
      .isDefined

  def subresources: Set[Subresource] =
    actions
      .map(action => (action, action.endpointType))
      .collect { case (action, s: SubresourceEndpoint) =>
        (action, s)
      }
      .groupBy { case (_, s) => s.subresourceName }
      .filterKeys(_ != "status")
      .map { case (subresourceName, actions) =>
        val modelName =
          actions
            .map(_._2)
            .collectFirst {
              case EndpointType.PutSubresource(_, _, modelName, _, _)  => modelName
              case EndpointType.PostSubresource(_, _, modelName, _, _) => modelName
            }
            .getOrElse("String")

        Subresource(
          name = subresourceName,
          modelName = modelName,
          actions.map(_._1)
        )
      }
      .toSet

  def pluralEntityName: String = {
    val (_, entity) = splitName(modelName)
    English.plural(entity)
  }
}
case class UnsupportedResource(
  gvk: GroupVersionKind,
  actions: Set[IdentifiedAction],
  reason: String,
  unsupportedEndpoints: Set[IdentifiedAction]
) extends ClassifiedResource {

  def isGVK(groupVersionKind: GroupVersionKind): Boolean =
    this.gvk == groupVersionKind

  def isGVK(group: String, version: String, kind: String): Boolean =
    isGVK(GroupVersionKind(group, version, kind))

  def describe: String = {
    val supportedActions = actions diff unsupportedEndpoints
    val supportedDescs = supportedActions.map(action => s"  - ${action.describe}").mkString("\n")
    val unsupportedDescs = unsupportedEndpoints
      .map(action => s"  - ${action.describe} - ${getReason(action.endpointType)}")
      .mkString("\n")
    s"(${gvk.group}/${gvk.version}/${gvk.kind}) $reason, actions:\n$supportedDescs\n$unsupportedDescs"
  }

  private def getReason(endpointType: EndpointType): String =
    endpointType match {
      case EndpointType.Unsupported(reason) => reason
      case _                                => "???"
    }
}

case class Subresource(
  name: String,
  modelName: String,
  actions: Set[IdentifiedAction]
) {
  def describe: String = s"$name ($modelName) [${actions.map(_.action).mkString(", ")}]"

  def id: SubresourceId =
    SubresourceId(name, modelName, actions.map(_.action))
}

object ClassifiedResource {
  def classifyActions(
    logger: sbt.Logger,
    definitionMap: Map[String, IdentifiedSchema],
    identified: Set[IdentifiedAction]
  ): Task[Set[SupportedResource]] = {
    val byPath: Map[String, IdentifiedAction] =
      identified.map(action => action.name -> action).toMap
    val rootGVKs: Map[IdentifiedAction, GroupVersionKind] =
      identified.map(action => action -> action.rootGVK(byPath)).toMap

    val groups = identified.groupBy(rootGVKs(_).group)

    val all = groups
      .map { case (group, paths) =>
        paths.groupBy(rootGVKs(_).kind).map { case (kind, paths) =>
          paths.groupBy(rootGVKs(_).version).map { case (version, actions) =>
            val groupVersionKind = GroupVersionKind(group, version, kind)
            val classification =
              classifyResource(logger, definitionMap, groupVersionKind, actions)

            classification match {
              case supported: SupportedResource     =>
                val endpointWhitelist =
                  classification.unsupportedEndpoints.map(Whitelist.isWhitelistedAction)
                val hasUnexpectedUnsupportedEndpoints = endpointWhitelist.contains(None)
                val whitelistedIssues = endpointWhitelist.collect { case Some(issueRef) =>
                  issueRef
                }

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
      }
      .toList
      .flatten
      .flatten
      .flatten

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
    gvk: GroupVersionKind,
    actions: Set[IdentifiedAction]
  ): ClassifiedResource = {
    val endpoints = actions.map(action => action.endpointType -> action).toMap
    val unsupportedEndpoints = actions
      .map(action => action -> action.endpointType)
      .collect { case (action, EndpointType.Unsupported(_)) => action }

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
          gvk,
          modelName,
          plural,
          modelReferences = refs,
          actions,
          unsupportedEndpoints
        )
      } else
        None

    classifyAs(true) orElse classifyAs(false) getOrElse UnsupportedResource(
      gvk,
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
      .collect {
        case t @ EndpointType.PutSubresource(subresourceName, _, _, _, _)
            if subresourceName == "status" =>
          t
      }
      .exists(t => t.namespaced == namespaced)
  private def hasGetStatus(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect {
        case t @ EndpointType.GetSubresource(subresourceName, _, _, _)
            if subresourceName == "status" =>
          t
      }
      .exists(t => t.namespaced == namespaced)
  private def hasDelete(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect { case t: EndpointType.Delete =>
        t
      }
      .exists(t => t.namespaced == namespaced)
  private def hasDeleteMany(endpoints: Set[EndpointType], namespaced: Boolean): Boolean =
    endpoints
      .collect { case t: EndpointType.DeleteMany =>
        t
      }
      .exists(t => t.namespaced == namespaced)

}
