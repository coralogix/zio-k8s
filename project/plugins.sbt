addSbtPlugin("org.scalameta"                     % "sbt-scalafmt"        % "2.5.6")
addSbtPlugin("com.github.sbt"                    % "sbt-ci-release"      % "1.11.2")
addSbtPlugin("com.github.sbt"                    % "sbt-native-packager" % "1.11.4")
addSbtPlugin("org.scalameta"                     % "sbt-mdoc"            % "2.8.1")
addSbtPlugin("com.github.sbt"                    % "sbt-unidoc"          % "0.6.0")
addSbtPlugin("com.eed3si9n"                      % "sbt-buildinfo"       % "0.12.0")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings"    % "3.0.2")
addSbtPlugin("ch.epfl.scala"                     % "sbt-scalafix"        % "0.14.4")

libraryDependencies += "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.11.1"

lazy val codegen = project
  .in(file("."))
  .dependsOn(ProjectRef(file("../zio-k8s-codegen"), "zio-k8s-codegen"))
