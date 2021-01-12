package com.coralogix.operator.logic.operators.coralogixlogger

import zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.Coralogixlogger
import zio.k8s.client.model.{ K8sResourceType, Object, ObjectTransformations }
import zio.k8s.client.model.syntax._
import zio.k8s.model.apps.v1.{ DaemonSet, DaemonSetSpec }
import zio.k8s.model.core.v1.{
  Container,
  EnvVar,
  HostPathVolumeSource,
  PodSpec,
  PodTemplateSpec,
  ResourceRequirements,
  SecurityContext,
  ServiceAccount,
  Volume,
  VolumeMount
}
import zio.k8s.model.pkg.api.resource.Quantity
import zio.k8s.model.pkg.apis.meta.v1.{ LabelSelector, ObjectMeta, OwnerReference }
import zio.k8s.model.rbac.v1.{ ClusterRole, ClusterRoleBinding, PolicyRule, RoleRef, Subject }

object Model {

  private def labelsFor(name: String): Option[Map[String, String]] =
    Some(
      Map(
        "k8s-app" -> s"fluentd-coralogix-$name"
      )
    )

  def attachOwner[T <: Object: ObjectTransformations](
    ownerName: String,
    ownerUid: String,
    ownerType: K8sResourceType,
    target: T
  ): T =
    target.mapMetadata(metadata =>
      metadata.copy(ownerReferences =
        Some(
          metadata.ownerReferences.getOrElse(Vector.empty) :+
            OwnerReference(
              apiVersion = s"${ownerType.group}/${ownerType.version}".stripPrefix("/"),
              kind = ownerType.resourceType,
              name = ownerName,
              uid = ownerUid,
              controller = Some(true),
              blockOwnerDeletion = Some(true)
            )
        )
      )
    )

  def serviceAccount(name: String, resource: Coralogixlogger): ServiceAccount =
    ServiceAccount(
      metadata = Some(
        ObjectMeta(
          name = Some("fluentd-coralogix-service-account"),
          namespace = resource.metadata.flatMap(_.namespace),
          labels = labelsFor(name)
        )
      )
    )

  def clusterRole(name: String, resource: Coralogixlogger): ClusterRole =
    ClusterRole(
      metadata = Some(
        ObjectMeta(
          name = Some("fluentd-coralogix-role"),
          namespace = resource.metadata.flatMap(_.namespace),
          labels = labelsFor(name)
        )
      ),
      rules = Some(
        Vector(
          PolicyRule(
            apiGroups = Some(Vector("")),
            resources = Some(
              Vector(
                "namespaces",
                "pods"
              )
            ),
            verbs = Vector(
              "get",
              "list",
              "watch"
            )
          )
        )
      )
    )

  def clusterRoleBinding(name: String, resource: Coralogixlogger): ClusterRoleBinding =
    ClusterRoleBinding(
      metadata = Some(
        ObjectMeta(
          name = Some("fluentd-coralogix-role-binding"),
          namespace = resource.metadata.flatMap(_.namespace),
          labels = labelsFor(name)
        )
      ),
      roleRef = RoleRef(
        apiGroup = "rbac.authorization.k8s.io",
        kind = "ClusterRole",
        name = "fluentd-coralogix-role"
      ),
      subjects = Some(
        Vector(
          Subject(
            kind = "ServiceAccount",
            name = "fluentd-coralogix-service-account",
            namespace = resource.metadata.flatMap(_.namespace)
          )
        )
      )
    )

  def daemonSet(name: String, resource: Coralogixlogger): DaemonSet =
    DaemonSet(
      metadata = Some(
        ObjectMeta(
          name = Some(s"fluentd-coralogix-$name"),
          namespace = resource.metadata.flatMap(_.namespace),
          labels = labelsFor(name).map(_ + ("kubernetes.io/cluster-service" -> "true"))
        )
      ),
      spec = Some(
        DaemonSetSpec(
          selector = LabelSelector(
            matchLabels = Some(
              Map(
                "k8s-app" -> s"fluentd-coralogix-$name"
              )
            )
          ),
          template = PodTemplateSpec(
            metadata = Some(
              ObjectMeta(
                labels = labelsFor(name).map(_ + ("kubernetes.io/cluster-service" -> "true"))
              )
            ),
            spec = Some(
              PodSpec(
                containers = Vector(
                  Container(
                    name = "fluentd",
                    image = Some("registry.connect.redhat.com/coralogix/coralogix-fluentd:1.0.0"),
                    imagePullPolicy = Some("Always"),
                    securityContext = Some(
                      SecurityContext(
                        runAsUser = Some(0L),
                        privileged = Some(true)
                      )
                    ),
                    env = Some(
                      Vector(
                        EnvVar(
                          name = "CORALOGIX_PRIVATE_KEY",
                          value = resource.spec.map(_.privateKey)
                        ),
                        EnvVar(
                          name = "CLUSTER_NAME",
                          value = resource.spec.map(_.clusterName)
                        )
                      )
                    ),
                    resources = Some(
                      ResourceRequirements(
                        requests = Some(
                          Map(
                            "cpu"    -> Quantity("100m"),
                            "memory" -> Quantity("400Mi")
                          )
                        )
                      )
                    ),
                    volumeMounts = Some(
                      Vector(
                        VolumeMount(
                          name = "varlog",
                          mountPath = "/var/log"
                        ),
                        VolumeMount(
                          name = "varlibdockercontainers",
                          mountPath = "/var/lib/docker/containers",
                          readOnly = Some(true)
                        )
                      )
                    )
                  )
                ),
                volumes = Some(
                  Vector(
                    Volume(
                      name = "varlog",
                      hostPath = Some(
                        HostPathVolumeSource(
                          path = "/var/log"
                        )
                      )
                    ),
                    Volume(
                      name = "varlibdockercontainers",
                      hostPath = Some(
                        HostPathVolumeSource(
                          path = "/var/lib/docker/containers"
                        )
                      )
                    )
                  )
                ),
                serviceAccountName = Some("fluentd-coralogix-service-account")
              )
            )
          )
        )
      )
    )
}
