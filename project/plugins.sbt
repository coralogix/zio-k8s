addSbtPlugin("org.scalameta"                     % "sbt-scalafmt"        % "2.4.4")
addSbtPlugin("com.github.sbt"                    % "sbt-ci-release"      % "1.5.10")
addSbtPlugin("com.github.sbt"                    % "sbt-native-packager" % "1.9.7")
addSbtPlugin("org.scalameta"                     % "sbt-mdoc"            % "2.2.24")
addSbtPlugin("com.github.sbt"                    % "sbt-unidoc"          % "0.5.0")
addSbtPlugin("com.eed3si9n"                      % "sbt-buildinfo"       % "0.10.0")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings"    % "3.0.0")

libraryDependencies += "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.11.1"

lazy val codegen = project
  .in(file("."))
  .dependsOn(ProjectRef(file("../zio-k8s-codegen"), "zio-k8s-codegen"))
