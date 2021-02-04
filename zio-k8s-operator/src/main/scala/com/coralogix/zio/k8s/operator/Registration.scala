package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.K8sFailure
import K8sFailure.syntax._
import com.coralogix.zio.k8s.client.apiextensions.v1.{ customresourcedefinitions => crd }
import com.coralogix.zio.k8s.client.model.ResourceMetadata
import zio.ZIO
import zio.blocking.Blocking
import com.coralogix.zio.k8s.client.apiextensions.v1.customresourcedefinitions.CustomResourceDefinitions
import com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition
import zio.logging.{ log, Logging }

object Registration {
  def registerIfMissing[T](
    customResourceDefinition: ZIO[Blocking, Throwable, CustomResourceDefinition]
  )(implicit
    metadata: ResourceMetadata[T]
  ): ZIO[Logging with Blocking with CustomResourceDefinitions, Throwable, Unit] = {
    val name = s"${metadata.resourceType.resourceType}.${metadata.resourceType.group}"
    log.info(s"Checking that $name CRD is registered") *>
      ZIO.whenM(
        crd
          .get(name)
          .ifFound
          .bimap(registrationFailure, _.isEmpty)
      )(register(customResourceDefinition))
  }

  private def register(
    customResourceDefinition: ZIO[Logging with Blocking, Throwable, CustomResourceDefinition]
  ): ZIO[CustomResourceDefinitions with Logging with Blocking, Throwable, Unit] =
    for {
      definition <- customResourceDefinition
      _          <- crd.create(definition).mapError(registrationFailure)
      name       <- definition.getName.mapError(registrationFailure)
      _          <- log.info(s"Registered $name CRD")
    } yield ()

  private def registrationFailure(failure: K8sFailure): Throwable =
    new RuntimeException(
      s"CRD registration failed",
      OperatorFailure.toThrowable[Nothing].toThrowable(KubernetesFailure(failure))
    )
}
