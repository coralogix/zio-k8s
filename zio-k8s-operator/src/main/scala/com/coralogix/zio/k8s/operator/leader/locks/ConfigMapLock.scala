package com.coralogix.zio.k8s.operator.leader.locks

import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.{ K8sFailure, Resource }
import com.coralogix.zio.k8s.model.core.v1.ConfigMap
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import zio.Schedule

class ConfigMapLock(
  lockName: String,
  retryPolicy: Schedule[Any, K8sFailure, Unit],
  deleteOnRelease: Boolean = true,
  configmaps: ConfigMaps.Service
) extends LeaderForLifeLock[ConfigMap](lockName, retryPolicy, deleteOnRelease) {

  override protected val client: Resource[ConfigMap] = configmaps.asGenericResource

  protected override def makeLock: ConfigMap = ConfigMap(metadata = ObjectMeta())

}
