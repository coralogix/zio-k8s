package com.coralogix.zio.k8s

import com.coralogix.zio.k8s.client.kubernetes.{ Kubernetes, KubernetesApi }
import zio.{ Has, Tag, ZLayer }

package object client {
  final implicit class K8sApiLayerOps[R, E, A](val self: ZLayer[R, E, Kubernetes]) extends AnyVal {
    def focus[B]: FocusApi[R, E, B] = new FocusApi[R, E, B] {
      override def apply[BImpl <: B](f: KubernetesApi => BImpl)(implicit
        tag: Tag[B]
      ): ZLayer[R, E, Has[B]] =
        self.map(a => Has(f(a.get).asInstanceOf[B]))
    }
  }

  trait FocusApi[R, E, B] {
    def apply[BImpl <: B](f: KubernetesApi => BImpl)(implicit tag: Tag[B]): ZLayer[R, E, Has[B]]
  }
}
