import com.coralogix.zio.k8s.client.io.fluxcd.helm.definitions.helmrelease.v1._
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta

object Test {
  val test = HelmRelease(
    metadata = ObjectMeta(name = "test"),
    spec = HelmRelease.Spec(
      chart = HelmRelease.Spec.Chart(),
      rollback = HelmRelease.Spec.Rollback(
        wait_ = true
      )
    )
  )
}
