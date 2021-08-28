externalCustomResourceDefinitions := Seq(
  file("crds/keda.sh_clustertriggerauthentications.yaml"),
  file("crds/keda.sh_scaledjobs.yaml"),
  file("crds/keda.sh_scaledobjects.yaml"),
  file("crds/keda.sh_triggerauthentications.yaml")
)

enablePlugins(K8sCustomResourceCodegenPlugin)

val pluginVersion = System.getProperty("plugin.version")
libraryDependencies +=
  "com.coralogix" %% "zio-k8s-client" % pluginVersion
