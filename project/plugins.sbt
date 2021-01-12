addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")

lazy val codegen = project
  .in(file("."))
  .dependsOn(ProjectRef(file("../zio-k8s-codegen"), "zio-k8s-codegen"))
