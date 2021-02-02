package com.coralogix.zio.k8s.codegen.internal

object Whitelist {
  def isWhitelisted(unsupportedResource: UnsupportedResource): Option[IssueReference] =
    unsupportedResource.isGVK("", "v1", "PodProxyOptions").as(67) ||
      unsupportedResource.isGVK("", "v1", "ServiceProxyOptions").as(67) ||
      unsupportedResource.isGVK("", "v1", "NodeProxyOptions").as(67) ||
      unsupportedResource.isGVK("", "v1", "PodPortForwardOptions").as(66) ||
      unsupportedResource.isGVK("", "v1", "PodExecOptions").as(65) ||
      unsupportedResource.isGVK("", "v1", "PodAttachOptions").as(64) ||
      unsupportedResource.isGVK("", "v1", "ComponentStatus").as(63) ||
      unsupportedResource.isGVK("", "v1", "Binding").as(72) ||
      unsupportedResource
        .isGVK("authorization.k8s.io", "v1beta1", "SelfSubjectRulesReview")
        .as(62) ||
      unsupportedResource.isGVK("authorization.k8s.io", "v1", "SelfSubjectRulesReview").as(62) ||
      unsupportedResource.isGVK("authorization.k8s.io", "v1beta1", "SubjectAccessReview").as(62) ||
      unsupportedResource.isGVK("authorization.k8s.io", "v1", "SubjectAccessReview").as(62) ||
      unsupportedResource
        .isGVK("authorization.k8s.io", "v1beta1", "SelfSubjectAccessReview")
        .as(62) ||
      unsupportedResource.isGVK("authorization.k8s.io", "v1", "SelfSubjectAccessReview").as(62) ||
      unsupportedResource
        .isGVK("authorization.k8s.io", "v1beta1", "LocalSubjectAccessReview")
        .as(62) ||
      unsupportedResource.isGVK("authorization.k8s.io", "v1", "LocalSubjectAccessReview").as(62) ||
      unsupportedResource.isGVK("authentication.k8s.io", "v1beta1", "TokenReview").as(61) ||
      unsupportedResource.isGVK("authentication.k8s.io", "v1", "TokenReview").as(61) ||
      unsupportedResource.isGVK("authentication.k8s.io", "v1", "TokenRequest").as(61)

  def isWhitelistedAction(action: IdentifiedAction): Option[IssueReference] =
    (action.action == "patch").as(32) ||
      (action.action == "watchlist").as(58) ||
      (action.action == "watch").as(58) ||
      (action.action == "deletecollection").as(25)

  def isWhitelistedPath(
    path: IdentifiedPath
  ): Option[IssueReference] = // IdentifiedPath => Option[IssueReference]
    path match {
      case RegularAction("/.well-known/openid-configuration/", _, _) => Some(IssueReference(60))
      case RegularAction("/openid/v1/jwks/", _, _)                   => Some(IssueReference(60))
      case RegularAction("/logs/", _, _)                             => Some(IssueReference(59))
      case RegularAction("/logs/{logpath}", _, _)                    => Some(IssueReference(59))
      case _: ApiGroupInfo                                           => Some(IssueReference(59))
      case _: ApiVersionInfo                                         => Some(IssueReference(59))
      case _: ApiResourceListing                                     => Some(IssueReference(59))
      case _: ApiGroupListing                                        => Some(IssueReference(59))
      case _: GetKubernetesVesion                                    => Some(IssueReference(59))
      case _                                                         => None
    }

  case class IssueReference(id: Int) {
    def url: String = s"https://github.com/coralogix/zio-k8s/issues/$id"
  }

  private implicit class BoolOps(value: Boolean) {
    def as(issueId: Int): Option[IssueReference] =
      if (value) Some(IssueReference(issueId)) else None
  }

  private implicit class OptionIssueReferenceOps(value: Option[IssueReference]) {
    def ||(other: Option[IssueReference]): Option[IssueReference] = value orElse other
  }
}
