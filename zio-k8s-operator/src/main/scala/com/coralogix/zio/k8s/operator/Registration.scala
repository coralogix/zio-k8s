package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.K8sFailure
import K8sFailure.syntax._
import com.coralogix.zio.k8s.client.apiextensions.v1.{ customresourcedefinitions => crd }
import com.coralogix.zio.k8s.client.model.ResourceMetadata
import zio.ZIO

import com.coralogix.zio.k8s.client.apiextensions.v1.customresourcedefinitions.CustomResourceDefinitions
import com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition
import zio.logging.{ log, Logging }

/** Registers CRD objects
  */
object Registration {

  /** Register a Custom Resource Definition if it is not registered yet
    *
    * When using the zio-k8s-crd plugin, the effect to provide the custom resource definition is
    * automatically generated.
    *
    * @param customResourceDefinition
    *   Effect returning the custom resource definition
    * @param metadata
    *   Resource metadata
    * @tparam T
    *   Resource type
    */
  def registerIfMissing[T](
    customResourceDefinition: ZIO[Any, Throwable, CustomResourceDefinition]
  )(implicit
    metadata: ResourceMetadata[T]
  ): ZIO[Logging with Any with CustomResourceDefinitions, Throwable, Unit] = {
    val name = s"${metadata.resourceType.resourceType}.${metadata.resourceType.group}"
    log.info(s"Checking that $name CRD is registered") *>
      ZIO.whenZIO(
        crd
          .get(name)
          .ifFound
          .mapBoth(registrationFailure, _.isEmpty)
      )(register(customResourceDefinition))
  }

  private def register(
    customResourceDefinition: ZIO[Logging with Any, Throwable, CustomResourceDefinition]
  ): ZIO[CustomResourceDefinitions with Logging with Any, Throwable, Unit] =
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
