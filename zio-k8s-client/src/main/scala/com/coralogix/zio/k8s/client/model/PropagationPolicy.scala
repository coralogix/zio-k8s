package com.coralogix.zio.k8s.client.model

sealed trait PropagationPolicy
object PropagationPolicy {
  case object Orphan extends PropagationPolicy
  case object Background extends PropagationPolicy
  case object Foreground extends PropagationPolicy
}
