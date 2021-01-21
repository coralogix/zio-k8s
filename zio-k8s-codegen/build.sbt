sbtPlugin := true

organization := "com.coralogix"
name         := "zio-k8s-codegen"

scalaVersion := "2.12.12"

Compile / unmanagedSourceDirectories += baseDirectory.value / "src/shared/scala"

libraryDependencies ++= Seq(
  "dev.zio"             %% "zio"              % "1.0.4",
  "dev.zio"             %% "zio-nio"          % "1.0.0-RC10",
  "io.swagger.parser.v3" % "swagger-parser"   % "2.0.24",
  "io.circe"            %% "circe-core"       % "0.13.0",
  "io.circe"            %% "circe-parser"     % "0.13.0",
  "io.circe"            %% "circe-yaml"       % "0.13.1",
  "org.scalameta"       %% "scalameta"        % "4.3.21",
  "org.scalameta"       %% "scalafmt-dynamic" % "2.7.5"
)
