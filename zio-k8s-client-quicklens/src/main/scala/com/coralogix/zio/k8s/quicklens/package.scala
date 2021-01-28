package com.coralogix.zio.k8s

import com.coralogix.zio.k8s.client.model.Optional
import com.softwaremill.quicklens.{ QuicklensFunctor, QuicklensSingleAtFunctor }

import java.util.NoSuchElementException

package object quicklens {

  implicit def optionalFunctor[A]
    : QuicklensFunctor[Optional, A] with QuicklensSingleAtFunctor[Optional, A] =
    new QuicklensFunctor[Optional, A] with QuicklensSingleAtFunctor[Optional, A] {
      override def map(fa: Optional[A])(f: A => A): Optional[A] =
        fa.map(f)
      override def at(fa: Optional[A])(f: A => A): Optional[A] =
        Optional.Present(fa.map(f) match {
          case Optional.Present(get) => get
          case Optional.Absent       => throw new NoSuchElementException
        })
      override def atOrElse(fa: Optional[A], default: => A)(f: A => A): Optional[A] =
        fa.orElse(Optional.Present(default)).map(f)
      override def index(fa: Optional[A])(f: A => A): Optional[A] = fa.map(f)
    }
}
