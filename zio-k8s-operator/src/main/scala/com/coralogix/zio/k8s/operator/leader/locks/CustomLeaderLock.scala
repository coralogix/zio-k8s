package com.coralogix.zio.k8s.operator.leader.locks

import com.coralogix.zio.k8s.client.{ K8sFailure, Resource }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import com.coralogix.zio.k8s.operator.leader.locks.leaderlockresources.LeaderLockResources
import zio.Schedule

class CustomLeaderLock(
  lockName: String,
  retryPolicy: Schedule[Any, K8sFailure, Unit],
  deleteOnRelease: Boolean = true,
  leaderlockresources: LeaderLockResources.Service
) extends LeaderForLifeLock[LeaderLockResource](lockName, retryPolicy, deleteOnRelease) {

  override protected val client: Resource[LeaderLockResource] =
    leaderlockresources.asGenericResource

  protected override def makeLock: LeaderLockResource = LeaderLockResource(ObjectMeta())

}
