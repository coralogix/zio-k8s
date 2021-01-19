
object Test {
  import com.coralogix.zio.k8s.client.com.example.stable.definitions.crontab.v1.CronTab

  val test = CronTab(Some(
    CronTab.Spec(
      cronSpec = Some("x"),
      image = Some("y"),
      replicas = None
    )
  ))

  val test2 = test.mapMetadata(_.copy(name = Some("name")))
}