package com.coralogix.zio.k8s.operator.leader.locks

import zio.Duration

import java.time.OffsetDateTime

case class LeaderElectionRecord(
  holderIdentity: String,
  leaseDuration: Duration,
  acquireTime: OffsetDateTime,
  renewTime: OffsetDateTime,
  leaderTransitions: Int
)
