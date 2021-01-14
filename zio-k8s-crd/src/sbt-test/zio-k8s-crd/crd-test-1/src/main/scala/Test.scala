
object Test {
  import com.coralogix.zio.k8s.client.com.example.stable.definitions.crontab.v1.Crontab

  val test = Crontab(Some(
    Crontab.Spec(
      cronSpec = Some("x"),
      image = Some("y"),
      replicas = None
    )
  ))

  val test2 = test.mapMetadata(_.copy(name = Some("name")))
}