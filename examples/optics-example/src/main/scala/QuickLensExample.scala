import com.coralogix.zio.k8s.quicklens._
import com.coralogix.zio.k8s.model.core.v1.{ Container, Pod, PodSpec }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import com.softwaremill.quicklens._

object QuickLensExample extends App {

  val pod = Pod(
    metadata = ObjectMeta(
      name = "test-pod"
    ),
    spec = PodSpec(
      containers = Vector(
        Container(
          name = "test-container-1"
        )
      )
    )
  )

  val pod2 = pod.modify(_.metadata.each.namespace).setTo("namespace-1")

  println(pod2)
}
