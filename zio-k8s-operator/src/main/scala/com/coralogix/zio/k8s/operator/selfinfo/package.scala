package com.coralogix.zio.k8s.operator

import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.model.core.v1.Pod
import com.coralogix.zio.k8s.operator.OperatorFailure.k8sFailureToThrowable
import com.coralogix.zio.k8s.operator.OperatorLogging.ConvertableToThrowable

import zio.nio.file.Path
import zio.nio.file.Files
import zio.{ IO, ULayer, ZIO, ZLayer }

import java.io.IOException
import zio.System

package object contextinfo {

  type ContextInfo = ContextInfo.Service

  object ContextInfo {
    trait Service {
      def namespace: IO[ContextInfoFailure, K8sNamespace]

      def pod: IO[ContextInfoFailure, Pod]
    }

    abstract class LiveBase(pods: Pods.Service) extends Service {
      override def pod: IO[ContextInfoFailure, Pod] =
        for {
          ns           <- namespace
          maybePodName <- System
                            .env("POD_NAME")
                            .mapError(reason => ContextInfoFailure.PodNameMissing(Some(reason)))
          result       <- maybePodName match {
                            case Some(podName) =>
                              pods
                                .get(podName, ns)
                                .mapError(ContextInfoFailure.KubernetesError.apply)
                            case None          =>
                              ZIO.fail(ContextInfoFailure.PodNameMissing(None))
                          }
        } yield result
    }

    class Live(pods: Pods.Service) extends LiveBase(pods) {
      override def namespace: IO[ContextInfoFailure, K8sNamespace] =
        Files
          .readAllLines(Path("/var/run/secrets/kubernetes.io/serviceaccount/namespace"))
          .mapBoth(error => ContextInfoFailure.UnknownNamespace(Some(error)), _.headOption)
          .flatMap {
            case Some(line) => ZIO.succeed(K8sNamespace(line.trim))
            case None       => ZIO.fail(ContextInfoFailure.UnknownNamespace(None))
          }
    }

    class LiveForcedNamespace(pods: Pods.Service, ns: K8sNamespace) extends LiveBase(pods) {
      override def namespace: IO[ContextInfoFailure, K8sNamespace] = ZIO.succeed(ns)
    }

    zio.Clock
    val any: ZLayer[ContextInfo, Nothing, ContextInfo] = ZLayer.service[ContextInfo]

    val live: ZLayer[Any with Pods, ContextInfoFailure, ContextInfo] =
      ZLayer {
        for {
          pods <- ZIO.service[Pods.Service]
        } yield new Live(pods)
      }

    def liveForcedNamespace(
      namespace: K8sNamespace
    ): ZLayer[Pods, ContextInfoFailure, ContextInfo] =
      ZLayer {
        for {
          pods <- ZIO.service[Pods.Service]
        } yield new LiveForcedNamespace(pods, namespace)
      }

    def test(p: Pod, ns: K8sNamespace): ULayer[ContextInfo] =
      ZLayer.succeed(new Service {
        override def namespace: IO[ContextInfoFailure, K8sNamespace] = ZIO.succeed(ns)
        override def pod: IO[ContextInfoFailure, Pod] = ZIO.succeed(p)
      })
  }

  /** Possible failures of the the context-info gathering module
    */
  sealed trait ContextInfoFailure

  object ContextInfoFailure {

    /** Could not determine the namespace
      *
      * If it is not provided by the caller, the implementation tries to read it from
      * /var/run/secrets/kubernetes.io/serviceaccount/namespace.
      */
    final case class UnknownNamespace(reason: Option[IOException]) extends ContextInfoFailure

    /** Could not determine the running Pod's name. It has to be provided in the POD_NAME
      * environment variable.
      */
    final case class PodNameMissing(reason: Option[SecurityException]) extends ContextInfoFailure

    /** Failure while calling the Kubernetes API
      */
    final case class KubernetesError(error: K8sFailure) extends ContextInfoFailure

    implicit def contextInfoFailureToThrowable: ConvertableToThrowable[ContextInfoFailure] = {
      case UnknownNamespace(Some(reason)) =>
        new RuntimeException(s"Could not read namespace", reason)
      case UnknownNamespace(None)         =>
        new RuntimeException(s"Could not read namespace")
      case PodNameMissing(Some(reason))   =>
        new RuntimeException(s"Could not read the POD_NAME environment variable", reason)
      case PodNameMissing(None)           =>
        new RuntimeException(s"The POD_NAME environment variable is missing")
      case KubernetesError(error)         =>
        implicitly[ConvertableToThrowable[K8sFailure]].toThrowable(error)
    }
  }
}
