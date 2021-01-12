package com.coralogix.operator.logic.operators.rulegroupset

import zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.Rulegroupset
import zio.k8s.client.com.coralogix.rulegroupsets.{ v1 => rulegroupsets }
import zio.k8s.client.model._
import zio.k8s.client.model.primitives.{ RuleGroupId, RuleGroupName }
import zio.k8s.client.com.coralogix.rulegroupsets.v1.metadata
import com.coralogix.operator.logic.Operator._
import com.coralogix.operator.logic._
import com.coralogix.operator.logic.aspects._
import com.coralogix.operator.logic.operators.rulegroupset.ModelTransformations.toCreateRuleGroup
import com.coralogix.operator.logic.operators.rulegroupset.StatusUpdate.runStatusUpdates
import com.coralogix.operator.monitoring.OperatorMetrics
import com.coralogix.rules.grpc.external.v1.RuleGroupsService.ZioRuleGroupsService._
import com.coralogix.rules.grpc.external.v1.RuleGroupsService.{
  DeleteRuleGroupRequest,
  UpdateRuleGroupRequest
}
import zio.clock.Clock
import zio.k8s.client.{ NamespacedResource, NamespacedResourceStatus }
import zio.logging.{ log, Logging }
import zio.{ Has, ZIO }

object RulegroupsetOperator {

  /** The central rulegroupset event processor logic */
  private def eventProcessor(): EventProcessor[
    Logging with rulegroupsets.Rulegroupsets with RuleGroupsServiceClient,
    Rulegroupset
  ] =
    (ctx, event) =>
      event match {
        case Reseted =>
          ZIO.unit
        case Added(item) =>
          if (
            item.generation == 0L || // new item
            !item.status
              .flatMap(_.lastUploadedGeneration)
              .contains(item.generation) // already synchronized
          )
            for {
              updates <- createNewRuleGroups(item.spec.ruleGroupsSequence.toSet)
              _ <- applyStatusUpdates(
                     ctx,
                     item,
                     StatusUpdate.UpdateLastUploadedGeneration(
                       item.generation
                     ) +: updates
                   )
            } yield ()
          else
            log.debug(
              s"Rule group set '${item.metadata.flatMap(_.name).getOrElse("")}' with generation ${item.generation} is already added"
            )
        case Modified(item) =>
          withExpectedStatus(item) { status =>
            if (status.lastUploadedGeneration.getOrElse(0L) < item.generation) {
              val mappings = status.groupIds.getOrElse(Vector.empty)
              val byName =
                item.spec.ruleGroupsSequence.map(ruleGroup => ruleGroup.name -> ruleGroup).toMap
              val alreadyAssigned = mappingToMap(mappings)
              val toAdd = byName.keySet.diff(alreadyAssigned.keySet)
              val toRemove = alreadyAssigned -- byName.keysIterator
              val toUpdate = alreadyAssigned
                .flatMap {
                  case (name, status) =>
                    byName.get(name).map(data => name -> (status, data))
                }

              for {
                up0 <- modifyExistingRuleGroups(toUpdate)
                up1 <- createNewRuleGroups(toAdd.map(byName.apply))
                up2 <- deleteRuleGroups(toRemove)
                _ <- applyStatusUpdates(
                       ctx,
                       item,
                       StatusUpdate.UpdateLastUploadedGeneration(
                         item.generation
                       ) +: (up0 ++ up1 ++ up2)
                     )
              } yield ()
            } else
              log.debug(
                s"Skipping modification of rule group set '${item.metadata.flatMap(_.name).getOrElse("")}' with generation ${item.generation}"
              )
          }
        case Deleted(item) =>
          withExpectedStatus(item) { status =>
            val mappings = status.groupIds.getOrElse(Vector.empty)
            deleteRuleGroups(mappingToMap(mappings)).unit
          }
      }

  private def withExpectedStatus[R <: Logging, E](
    ruleGroupSet: Rulegroupset
  )(f: Rulegroupset.Status => ZIO[R, E, Unit]): ZIO[R, E, Unit] =
    ruleGroupSet.status match {
      case Some(status) =>
        f(status)
      case None =>
        log.warn(
          s"Rule group set '${ruleGroupSet.metadata.flatMap(_.name).getOrElse("")}' has no status information"
        )
    }

  private def modifyExistingRuleGroups(
    mappings: Map[RuleGroupName, (RuleGroupId, Rulegroupset.Spec.RuleGroupsSequence)]
  ): ZIO[RuleGroupsServiceClient with Logging, GrpcFailure, Vector[StatusUpdate]] =
    ZIO
      .foreachPar_(mappings.toVector) {
        case (ruleGroupName, (id, data)) =>
          for {
            _ <- log.info(s"Modifying rule group '${ruleGroupName.value}' (${id.value})")
            response <- RuleGroupsServiceClient
                          .updateRuleGroup(
                            UpdateRuleGroupRequest(
                              groupId = Some(id.value),
                              ruleGroup = Some(toCreateRuleGroup(data))
                            )
                          )
                          .mapError(GrpcFailure.apply)
            _ <-
              log.trace(
                s"Rules API response for modifying rule group '${ruleGroupName.value}' (${id.value}): $response"
              )
          } yield ()
      }
      .as(Vector.empty[StatusUpdate])

  private def createNewRuleGroups(
    ruleGroups: Set[Rulegroupset.Spec.RuleGroupsSequence]
  ): ZIO[RuleGroupsServiceClient with Logging, OperatorFailure, Vector[
    StatusUpdate
  ]] =
    ZIO
      .foreachPar(ruleGroups.toVector) { ruleGroup =>
        for {
          _ <- log.info(s"Creating rule group '${ruleGroup.name.value}'")
          groupResponse <- RuleGroupsServiceClient
                             .createRuleGroup(toCreateRuleGroup(ruleGroup))
                             .mapError(GrpcFailure.apply)
          _ <-
            log.trace(
              s"Rules API response for creating rules group '${ruleGroup.name.value}': $groupResponse"
            )
          groupId <- ZIO.fromEither(
                       groupResponse.ruleGroup
                         .flatMap(_.id)
                         .map(RuleGroupId.apply)
                         .toRight(UndefinedGrpcField("CreateRuleGroupResponse.ruleGroup.id"))
                     )
        } yield Vector(
          StatusUpdate.AddRuleGroupMapping(ruleGroup.name, groupId)
        )
      }
      .map(_.flatten)

  private def deleteRuleGroups(
    mappings: Map[RuleGroupName, RuleGroupId]
  ): ZIO[RuleGroupsServiceClient with Logging, GrpcFailure, Vector[StatusUpdate]] =
    ZIO.foreachPar(mappings.toVector) {
      case (name, id) =>
        for {
          _ <- log.info(s"Deleting rule group '${name.value}' (${id.value})'")
          response <- RuleGroupsServiceClient
                        .deleteRuleGroup(DeleteRuleGroupRequest(id.value))
                        .mapError(GrpcFailure.apply)
          _ <-
            log.trace(
              s"Rules API response for deleting rule group '${name.value}' (${id.value}): $response"
            )
        } yield StatusUpdate.DeleteRuleGroupMapping(name)
    }

  private def applyStatusUpdates(
    ctx: OperatorContext,
    resource: Rulegroupset,
    updates: Vector[StatusUpdate]
  ): ZIO[Logging with Has[
    NamespacedResourceStatus[Rulegroupset.Status, Rulegroupset]
  ], KubernetesFailure, Unit] = {
    val initialStatus =
      resource.status.getOrElse(Rulegroupset.Status(groupIds = Some(Vector.empty)))
    val updatedStatus = runStatusUpdates(initialStatus, updates)

    rulegroupsets
      .replaceStatus(
        resource,
        updatedStatus,
        resource.metadata
          .flatMap(_.namespace)
          .map(K8sNamespace.apply)
          .getOrElse(K8sNamespace.default)
      )
      .mapError(KubernetesFailure.apply)
      .unit
  }.when(updates.nonEmpty)

  private def mappingToMap(
    mappings: Vector[Rulegroupset.Status.GroupIds]
  ): Map[RuleGroupName, RuleGroupId] =
    mappings.map { mapping =>
      mapping.name -> mapping.id
    }.toMap

  def forNamespace(
    namespace: K8sNamespace,
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[rulegroupsets.Rulegroupsets, Nothing, Operator[
    Clock with Logging with rulegroupsets.Rulegroupsets with RuleGroupsServiceClient,
    Rulegroupset
  ]] =
    Operator.namespaced(
      eventProcessor() @@ logEvents @@ metered(metrics)
    )(Some(namespace), buffer)

  def forAllNamespaces(
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[rulegroupsets.Rulegroupsets, Nothing, Operator[
    Clock with Logging with rulegroupsets.Rulegroupsets with RuleGroupsServiceClient,
    Rulegroupset
  ]] =
    Operator.namespaced(
      eventProcessor() @@ logEvents @@ metered(metrics)
    )(None, buffer)

  def forTest(): ZIO[rulegroupsets.Rulegroupsets, Nothing, Operator[
    Logging with rulegroupsets.Rulegroupsets with RuleGroupsServiceClient,
    Rulegroupset
  ]] =
    Operator.namespaced(eventProcessor())(Some(K8sNamespace("default")), 256)
}
