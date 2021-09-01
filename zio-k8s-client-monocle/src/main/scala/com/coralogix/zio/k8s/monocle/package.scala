package com.coralogix.zio.k8s

import _root_.monocle.{ Lens, Optional }
import client.model.{ Optional => K8sOptional }

/** Provides support for Monocle for the Kubernetes data models
  *
  * This package contains objects for all Kubernetes resource data models with [[Lens]] and
  * [[Optional]] optics for all their fields.
  */
package object monocle {

  /** A Monocle [[Optional]] implementation for zio-k8s's custom Optional type
    */
  def optional[S, A](lens: Lens[S, K8sOptional[A]]): Optional[S, A] =
    Optional((s: S) => lens.get(s).toOption)((a: A) => lens.set(K8sOptional.Present(a)))

}
