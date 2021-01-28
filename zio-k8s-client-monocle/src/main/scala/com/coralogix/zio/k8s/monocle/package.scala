package com.coralogix.zio.k8s

import _root_.monocle.{ Lens, Optional }
import client.model.{ Optional => K8sOptional }

package object monocle {
  def optional[S, A](lens: Lens[S, K8sOptional[A]]): Optional[S, A] =
    Optional((s: S) => lens.get(s).toOption)((a: A) => lens.set(K8sOptional.Present(a)))

}
