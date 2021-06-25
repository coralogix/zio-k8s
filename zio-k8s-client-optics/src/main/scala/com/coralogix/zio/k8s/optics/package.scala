package com.coralogix.zio.k8s

import zio.optics._
import com.coralogix.zio.k8s.client.model.{ Optional => K8sOptional }

package object optics {

  def absent[A]: Prism[K8sOptional[A], Unit] =
    Prism(
      {
        case K8sOptional.Present(a) => Left(OpticFailure(s"Present($a) did not satisfy isAbsent"))
        case K8sOptional.Absent     => Right(())
      },
      _ => Right(K8sOptional.Absent)
    )

  def present[A, B]: ZPrism[K8sOptional[A], K8sOptional[B], A, B] =
    ZPrism(
      {
        case K8sOptional.Present(a) => Right(a)
        case K8sOptional.Absent     => Left((OpticFailure(s"Absent did not satisfy isPresent"), None))
      },
      b => Right(K8sOptional.Present(b))
    )

}
