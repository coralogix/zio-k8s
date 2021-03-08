package com.coralogix.zio.k8s.client.model

/** Propagation policy for resource deletion
  *
  * See https://kubernetes.io/docs/concepts/workloads/controllers/garbage-collection/
  */
sealed trait PropagationPolicy
object PropagationPolicy {

  /** Deletes the object without deleting its dependents
    */
  case object Orphan extends PropagationPolicy

  /** Foreground cascading deletion
    */
  case object Background extends PropagationPolicy

  /** Background cascading deletion
    */
  case object Foreground extends PropagationPolicy
}
