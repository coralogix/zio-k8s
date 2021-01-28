import com.coralogix.zio.k8s.model.core.v1.{ Container, Pod, PodSpec }
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import com.coralogix.zio.k8s.monocle.core.v1.PodO._
import com.coralogix.zio.k8s.monocle.pkg.apis.meta.v1.ObjectMetaO._

object MonocleExample extends App {

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

  val f = (metadataO composeOptional namespaceO).set("namespace-1")

  println(f(pod))
}
