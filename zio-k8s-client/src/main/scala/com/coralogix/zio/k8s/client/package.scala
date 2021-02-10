package com.coralogix.zio.k8s

import com.coralogix.zio.k8s.client.kubernetes.{ Kubernetes, KubernetesApi }
import zio.{ Has, Tag, ZLayer }

package object client {
  final implicit class K8sApiLayerOps[R, E, A](val self: ZLayer[R, E, Kubernetes]) extends AnyVal {
    def narrow[B: Tag](f: KubernetesApi => B): ZLayer[R, E, Has[B]] =
      self.map(a => Has(f(a.get)))
  }
}
