package com.coralogix.zio.k8s.operator.leader

import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.model.core.v1.Pod
import zio.ZManaged
import zio.Clock

/** Common interface for different lock implementations used for leader election.
  */
trait LeaderLock {
  def acquireLock(
    namespace: K8sNamespace,
    pod: Pod
  ): ZManaged[Clock, LeaderElectionFailure[Nothing], Unit]
}
