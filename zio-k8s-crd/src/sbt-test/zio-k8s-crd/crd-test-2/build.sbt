externalCustomResourceDefinitions := Seq(
  file("crds/crontab.yaml")
)

enablePlugins(K8sCustomResourceCodegenPlugin)

val pluginVersion = System.getProperty("plugin.version")
libraryDependencies +=
  "com.coralogix" %% "zio-k8s-client" % pluginVersion
