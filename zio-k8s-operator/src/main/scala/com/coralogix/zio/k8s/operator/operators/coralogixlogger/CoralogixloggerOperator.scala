package com.coralogix.operator.logic.operators.coralogixlogger

import com.coralogix.operator.logic.aspects._
import com.coralogix.operator.logic.{ KubernetesFailure, Operator, OperatorFailure }
import com.coralogix.operator.logic.Operator.{ EventProcessor, OperatorContext }
import com.coralogix.operator.monitoring.OperatorMetrics
import zio.{ Has, ZIO }
import zio.clock.Clock
import zio.k8s.client.{ K8sFailure, NamespacedResourceStatus }
import zio.k8s.client.K8sFailure.syntax._
import zio.k8s.client.com.coralogix.loggers.coralogixloggers.{ v1 => coralogixloggers }
import zio.k8s.client.com.coralogix.loggers.coralogixloggers.v1.{ metadata, Coralogixloggers }
import zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.Coralogixlogger
import zio.k8s.client.serviceaccounts.{ v1 => serviceaccounts }
import zio.k8s.client.model.{ Added, Deleted, K8sNamespace, Modified, Reseted }
import zio.k8s.client.serviceaccounts.v1.ServiceAccounts
import zio.logging.{ log, Logging }

object CoralogixloggerOperator {
  private def eventProcessor(): EventProcessor[
    Logging with Coralogixloggers with ServiceAccounts,
    Coralogixlogger
  ] =
    (ctx, event) =>
      event match {
        case Reseted =>
          ZIO.unit
        case Added(item) =>
          setupLogger(ctx, item)
        case Modified(item) =>
          setupLogger(ctx, item)
        case Deleted(item) =>
          // The generated items are owned by the logger and get automatically garbage collected
          ZIO.unit
      }

  private def setupLogger(
    ctx: OperatorContext,
    resource: Coralogixlogger
  ): ZIO[Logging with Coralogixloggers with ServiceAccounts, OperatorFailure, Unit] =
    skipIfAlredyRunning(resource) {
      for {
        name <- resource.getName.mapError(KubernetesFailure.apply)
        uid  <- resource.getUid.mapError(KubernetesFailure.apply)
        _ <-
          updateState(
            resource,
            "PENDING",
            "Initializing Provision",
            s"Provisioning of '$name' in namespace '${resource.metadata.flatMap(_.namespace).getOrElse("-")}'"
          )
        _ <- setupServiceAccount(ctx, name, uid, resource)
      } yield ()
    }

  private def skipIfAlredyRunning[R <: Logging](resource: Coralogixlogger)(
    f: ZIO[R, OperatorFailure, Unit]
  ): ZIO[R, OperatorFailure, Unit] =
    if (resource.status.flatMap(_.state).contains("RUNNING"))
      log.info(s"CoralogixLogger is already running")
    else
      f

  private def updateState(
    resource: Coralogixlogger,
    newState: String,
    newPhase: String,
    newReason: String
  ): ZIO[Coralogixloggers, OperatorFailure, Coralogixlogger] = {
    val oldStatus = resource.status.getOrElse(Coralogixlogger.Status())
    val replacedStatus = oldStatus.copy(
      state = Some(newState),
      phase = Some(newPhase),
      reason = Some(newReason)
    )
    coralogixloggers
      .replaceStatus(
        resource,
        replacedStatus,
        resource.metadata
          .flatMap(_.namespace)
          .map(K8sNamespace.apply)
          .getOrElse(K8sNamespace.default)
      )
      .mapError(KubernetesFailure.apply)
  }

  private def setupServiceAccount(
    ctx: OperatorContext,
    name: String,
    uid: String,
    resource: Coralogixlogger
  ): ZIO[ServiceAccounts, OperatorFailure, Unit] = {
    val serviceAccount =
      Model.attachOwner(name, uid, ctx.resourceType, Model.serviceAccount(name, resource))

    for {
      serviceAccountName <- serviceAccount.getName.mapError(KubernetesFailure.apply)
      namespace = resource.metadata
                    .flatMap(_.namespace)
                    .map(K8sNamespace.apply)
                    .getOrElse(K8sNamespace.default)
      queryResult <- serviceaccounts
                       .get(serviceAccountName, namespace)
                       .ifFound
                       .either
      _ <- queryResult match {
             case Left(failure) => ZIO.unit // TODO
             case Right(None)   => ZIO.unit // TODO
             case Right(_)      => ZIO.unit // TODO
           }
    } yield ()
  }

  def forNamespace(
    namespace: K8sNamespace,
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[Coralogixloggers, Nothing, Operator[
    Clock with Logging with Coralogixloggers with ServiceAccounts,
    Coralogixlogger
  ]] =
    Operator.namespaced(
      eventProcessor() @@ logEvents @@ metered(metrics)
    )(Some(namespace), buffer)

  def forAllNamespaces(
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[Coralogixloggers, Nothing, Operator[
    Clock with Logging with Coralogixloggers with ServiceAccounts,
    Coralogixlogger
  ]] =
    Operator.namespaced(
      eventProcessor() @@ logEvents @@ metered(metrics)
    )(None, buffer)
}
