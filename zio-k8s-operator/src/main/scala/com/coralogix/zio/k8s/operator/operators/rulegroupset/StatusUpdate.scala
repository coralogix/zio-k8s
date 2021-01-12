package com.coralogix.operator.logic.operators.rulegroupset

import zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.Rulegroupset
import zio.k8s.client.model.primitives.{ RuleGroupId, RuleGroupName }
import com.softwaremill.quicklens._

/** Update actions for the Rulegroupset.Status subresource */
sealed trait StatusUpdate
object StatusUpdate {
  final case class AddRuleGroupMapping(name: RuleGroupName, id: RuleGroupId) extends StatusUpdate
  final case class DeleteRuleGroupMapping(name: RuleGroupName) extends StatusUpdate
  final case class UpdateLastUploadedGeneration(generation: Long) extends StatusUpdate

  private def runStatusUpdate(
    status: Rulegroupset.Status,
    update: StatusUpdate
  ): Rulegroupset.Status =
    update match {
      case StatusUpdate.AddRuleGroupMapping(name, id) =>
        modify(runStatusUpdate(status, DeleteRuleGroupMapping(name)))(
          _.groupIds.atOrElse(Vector.empty)
        )(_ :+ Rulegroupset.Status.GroupIds(name, id))
      case StatusUpdate.DeleteRuleGroupMapping(name) =>
        modify(status)(_.groupIds.each)(_.filterNot(_.name == name))
      case StatusUpdate.UpdateLastUploadedGeneration(generation) =>
        modify(status)(_.lastUploadedGeneration).setTo(Some(generation))
    }

  def runStatusUpdates(
    status: Rulegroupset.Status,
    updates: Vector[StatusUpdate]
  ): Rulegroupset.Status =
    updates.foldLeft(status)(runStatusUpdate)

}
