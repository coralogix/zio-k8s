sbtPlugin := true

organization := "com.coralogix"
name         := "zio-k8s-codegen"

scalaVersion := "2.12.15"

Compile / unmanagedSourceDirectories += baseDirectory.value / "src/shared/scala"

scalacOptions ++= Seq("-feature", "-deprecation")

libraryDependencies ++= Seq(
  "dev.zio"             %% "zio"              % "1.0.13",
  "dev.zio"             %% "zio-nio"          % "1.0.0-RC12",
  "io.swagger.parser.v3" % "swagger-parser"   % "2.0.24",
  "io.circe"            %% "circe-core"       % "0.14.1",
  "io.circe"            %% "circe-parser"     % "0.14.1",
  "io.circe"            %% "circe-yaml"       % "0.14.1",
  "org.atteo"            % "evo-inflector"    % "1.3",
  "io.github.vigoo"     %% "metagen-core"     % "0.0.9"
)
