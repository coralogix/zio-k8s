import com.coralogix.zio.k8s.client.com.example.stable.definitions.crontab.v1.CronTab
import com.coralogix.zio.k8s.client.com.example.stable.v1.crontabs
import com.coralogix.zio.k8s.model.autoscaling.v1.Scale
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.subresources.autoscaling.v1.NamespacedScaleSubresource
import zio._

object Test {

  val test = CronTab(
    CronTab.Spec(
      cronSpec = "x",
      image = "y",
      replicas = None,
    )
  )

  val scale: ZIO[Has[NamespacedScaleSubresource[CronTab]], K8sFailure, Scale] = crontabs.getScale("name", K8sNamespace.default)
}