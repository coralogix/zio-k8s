package com.coralogix.zio.k8s.examples.leader

import com.coralogix.zio.k8s.client.apiextensions.v1.customresourcedefinitions.CustomResourceDefinitions
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import com.coralogix.zio.k8s.client.coordination.v1.leases.Leases
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.operator.contextinfo.ContextInfo
import com.coralogix.zio.k8s.operator.leader.LeaderElection
import com.coralogix.zio.k8s.operator.leader.locks.LeaderLockResource
import com.coralogix.zio.k8s.operator.{ leader, Registration }
import zio.{ ZIOAppDefault, _ }

import scala.languageFeature.implicitConversions

object LeaderExample extends ZIOAppDefault {
  case class Config(k8s: K8sClusterConfig)

  override def run = {
    // Logging

    // Pods and ConfigMaps API
    val pods = k8sDefault >>> Pods.live
    val leases = k8sDefault >>> Leases.live
    val crds = k8sDefault >>> CustomResourceDefinitions.live
    val contextInfo =
      pods >>> ContextInfo.live.mapError(f => FiberFailure(Cause.fail(f)))
    val leaderElection =
      (leases ++ contextInfo) >>> LeaderElection.leaseLock("leader-example-lock")

    // Example code
    val program =
      Registration.registerIfMissing[LeaderLockResource](
        LeaderLockResource.customResourceDefinition
      ) *>
        example()

    program
      .provideSomeLayer(crds ++ leaderElection)
      .exitCode
  }

  private def example(): ZIO[
    LeaderElection.Service,
    Nothing,
    Option[Nothing]
  ] =
    leader
      .runAsLeader {
        exampleLeader()
      }
      .repeatWhile(_.isEmpty)

  private def exampleLeader(): ZIO[Any, Nothing, Nothing] =
    ZIO.logInfo(s"Got leader role") *> ZIO.never
}
