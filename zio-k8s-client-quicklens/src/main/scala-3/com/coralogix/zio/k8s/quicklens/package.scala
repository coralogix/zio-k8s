package com.coralogix.zio.k8s

import zio.prelude.data.Optional
import com.softwaremill.quicklens.{ QuicklensFunctor, QuicklensSingleAtFunctor }

import java.util.NoSuchElementException

/** Implements support for zio-k8s' custom [[Optional]] type to be used
  * with QuickLens
  */
package object quicklens {

  given QuicklensSingleAtFunctor[Optional] with {
    override def at[A](fa: Optional[A], f: A => A): Optional[A] =
      Optional.Present(fa.map(f) match {
        case Optional.Present(get) => get
        case Optional.Absent       => throw new NoSuchElementException
      })
    override def atOrElse[A](fa: Optional[A], f: A => A, default: => A): Optional[A] = fa.orElse(Optional.Present(default)).map(f)
    override def index[A](fa: Optional[A], f: A => A): Optional[A] = fa.map(f)
  }

  given QuicklensFunctor[Optional] with {
    def map[A, B](fa: Optional[A], f: A => B): Optional[B] = fa.map(f)
  }
}
