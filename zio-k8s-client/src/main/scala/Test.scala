import com.coralogix.zio.k8s.client.{ K8sFailure, UndefinedField }
import com.coralogix.zio.k8s.client.model.Optional
import com.coralogix.zio.k8s.model.apps.v1.{ DaemonSet, DaemonSetSpec }
import com.coralogix.zio.k8s.model.core.v1.{ PodSpec, PodTemplateSpec }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ LabelSelector, ObjectMeta }
import zio.{ IO, ZIO }

object Test {

  val daemonSet: DaemonSet =
    DaemonSet(
      metadata = ObjectMeta(
        name = "something"
      ),
      spec = DaemonSetSpec(
        template = PodTemplateSpec(
          spec = PodSpec(
            containers = Vector.empty
          )
        ),
        selector = LabelSelector(
          matchLabels = Map("x" -> "y")
        )
      )
    )

  val n1: IO[K8sFailure, String] = for {
    m <- daemonSet.getMetadata
    n <- m.getName
  } yield n

  val n2: Optional[String] = daemonSet.metadata.flatMap(_.name)
  val n3: Option[String] = daemonSet.metadata.flatMap(_.name).toOption

  def peek[A0, A1, A2](a0: A0)(f1: A0 => Optional[A1])(f2: A1 => Optional[A2]): Optional[A2] =
    f1(a0).flatMap(f2)
  def get[A0, A1, A2](a0: A0)(f1: A0 => Optional[A1])(f2: A1 => Optional[A2]): IO[K8sFailure, A2] =
    ZIO.fromEither(f1(a0).flatMap(f2).toRight(UndefinedField("???")))

  val n4 = peek(daemonSet)(_.metadata)(_.name)
  val n5 = get(daemonSet)(_.metadata)(_.name)

  case class Y(a: Optional[String] = Optional.Absent, b: Optional[Int] = Optional.Absent) {
    def setA(value: String): Y = copy(a = Optional.Present(value))
    def setB(value: Int): Y = copy(b = Optional.Present(value))
  }

  case class X(y: Optional[Y] = Optional.Absent) {
    def setY(f: Y => Y): X =
      y match {
        case Optional.Absent     => copy(y = f(Y()))
        case Optional.Present(_) => copy(y = y.map(f))
      }
  }

  def set[A0, A1, A2](a0: A0)(f1: A0 => (A1 => A1) => A0)(f2: A1 => A2 => A1)(value: A2): A0 =
    f1(a0)(a1 => f2(a1)(value))

  val x0 = X()
  val x1 = set(x0)(_.setY)(_.setA)("hello")
}
