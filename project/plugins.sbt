addSbtPlugin("org.scalameta" % "sbt-scalafmt"   % "2.4.2")
addSbtPlugin("com.geirsson"  % "sbt-ci-release" % "1.5.3")

libraryDependencies += "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.11.1"

lazy val codegen = project
  .in(file("."))
  .dependsOn(ProjectRef(file("../zio-k8s-codegen"), "zio-k8s-codegen"))
