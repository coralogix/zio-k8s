package com.coralogix.operator.logic.operators.rulegroupset

import zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.Rulegroupset
import zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.Rulegroupset.Spec
import zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.Rulegroupset.Spec.RuleGroupsSequence.AndSequence.OrGroup
import zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.Rulegroupset.Spec.RuleGroupsSequence.AndSequence.OrGroup.JsonExtract.DestField
import zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.Rulegroupset.Spec.RuleGroupsSequence.Matcher.Severities
import com.coralogix.rules.grpc.external.v1.JsonExtractParameters.DestinationField
import com.coralogix.rules.grpc.external.v1.RuleGroupsService.CreateRuleGroupRequest
import com.coralogix.rules.grpc.external.v1.RuleGroupsService.CreateRuleGroupRequest.CreateRuleSubgroup
import com.coralogix.rules.grpc.external.v1.RuleGroupsService.CreateRuleGroupRequest.CreateRuleSubgroup.CreateRule
import com.coralogix.rules.grpc.external.v1.RuleMatcher.Constraint
import com.coralogix.rules.grpc.external.v1.{
  AllowParameters,
  ApplicationNameConstraint,
  BlockParameters,
  ExtractParameters,
  JsonExtractParameters,
  ParseParameters,
  ReplaceParameters,
  RuleMatcher,
  RuleParameters,
  SeverityConstraint,
  SubsystemNameConstraint
}

/** Transformation between the gRPC rules API and the k8s CRD's models */
object ModelTransformations {
  private val Creator = "coralogix-kubernetes-operator"

  private def toSeverityConstraintValue(
    severity: Rulegroupset.Spec.RuleGroupsSequence.Matcher.Severities
  ): SeverityConstraint.Value.Recognized =
    severity match {
      case Severities.members.Debug    => SeverityConstraint.Value.DEBUG
      case Severities.members.Verbose  => SeverityConstraint.Value.VERBOSE
      case Severities.members.Info     => SeverityConstraint.Value.INFO
      case Severities.members.Warning  => SeverityConstraint.Value.WARNING
      case Severities.members.Error    => SeverityConstraint.Value.ERROR
      case Severities.members.Critical => SeverityConstraint.Value.CRITICAL
    }

  private def toGrpcRuleMatchers(
    matcher: Rulegroupset.Spec.RuleGroupsSequence.Matcher
  ): Seq[RuleMatcher] =
    (matcher.applications
      .map(
        _.map(name =>
          RuleMatcher(Constraint.ApplicationName(ApplicationNameConstraint(Some(name.value))))
        )
      )
      .toVector ++
      matcher.severities
        .map(
          _.map(severity =>
            RuleMatcher(
              Constraint.Severity(SeverityConstraint(toSeverityConstraintValue(severity)))
            )
          )
        )
        .toVector ++
      matcher.subsystems
        .map(
          _.map(name =>
            RuleMatcher(Constraint.SubsystemName(SubsystemNameConstraint(Some(name.value))))
          )
        )
        .toVector).flatten

  private def toDestinationField(field: DestField): DestinationField =
    field match {
      case DestField.members.Category   => DestinationField.CATEGORY
      case DestField.members.Classname  => DestinationField.CLASSNAME
      case DestField.members.Methodname => DestinationField.METHODNAME
      case DestField.members.Threadid   => DestinationField.THREADID
      case DestField.members.Severity   => DestinationField.SEVERITY
    }

  private def toParameters(rule: OrGroup): RuleParameters =
    RuleParameters(
      rule.extract.map(p =>
        RuleParameters.RuleParameters.ExtractParameters(ExtractParameters(rule = Some(p.rule)))
      ) orElse
        rule.jsonExtract.map(p =>
          RuleParameters.RuleParameters.JsonExtractParameters(
            JsonExtractParameters(toDestinationField(p.destField))
          )
        ) orElse
        rule.replace.map(p =>
          RuleParameters.RuleParameters.ReplaceParameters(
            ReplaceParameters(
              destinationField = Some(p.destField.name),
              replaceNewVal = Some(p.newValue),
              rule = Some(p.rule)
            )
          )
        ) orElse
        rule.parse.map(p =>
          RuleParameters.RuleParameters.ParseParameters(
            ParseParameters(destinationField = Some(p.destField.name), rule = Some(p.rule))
          )
        ) orElse
        rule.allow.map(p =>
          RuleParameters.RuleParameters.AllowParameters(
            AllowParameters(
              keepBlockedLogs = Some(p.keepBlockedLogs),
              rule = Some(p.rule)
            )
          )
        ) orElse
        rule.block.map(p =>
          RuleParameters.RuleParameters.BlockParameters(
            BlockParameters(keepBlockedLogs = Some(p.keepBlockedLogs), rule = Some(p.rule))
          )
        ) getOrElse RuleParameters.RuleParameters.Empty
    )

  private def toCreateRule(
    rule: Rulegroupset.Spec.RuleGroupsSequence.AndSequence.OrGroup,
    index: Int
  ): CreateRule =
    CreateRule(
      name = Some(rule.name.value),
      description = rule.description,
      sourceField = Some(rule.sourceField.name),
      parameters = Some(toParameters(rule)),
      enabled = Some(rule.enabled),
      order = Some(index)
    )

  private def toCreateRuleSubgroups(
    subGroup: Rulegroupset.Spec.RuleGroupsSequence.AndSequence,
    index: Int
  ): CreateRuleSubgroup =
    CreateRuleSubgroup(
      rules = subGroup.orGroup.zipWithIndex.map((toCreateRule _).tupled),
      enabled = None,
      order = Some(index)
    )

  def toCreateRuleGroup(ruleGroup: Spec.RuleGroupsSequence): CreateRuleGroupRequest =
    CreateRuleGroupRequest(
      name = Some(ruleGroup.name.value),
      description = ruleGroup.description,
      enabled = ruleGroup.enabled,
      hidden = ruleGroup.hidden,
      creator = Some(Creator),
      ruleMatchers = toGrpcRuleMatchers(ruleGroup.matcher),
      ruleSubgroups = ruleGroup.andSequence.zipWithIndex
        .map((toCreateRuleSubgroups _).tupled)
    )

}
