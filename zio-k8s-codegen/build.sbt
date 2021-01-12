sbtPlugin := true

organization := "com.coralogix"
name         := "zio-k8s-codegen"
version      := "0.1"

scalaVersion := "2.12.12"

libraryDependencies ++= Seq(
  "dev.zio"       %% "zio"              % "1.0.3",
  "dev.zio"       %% "zio-nio"          % "1.0.0-RC10",
  "com.twilio"    %% "guardrail"        % "0.61.0",
  "io.circe"      %% "circe-core"       % "0.13.0",
  "io.circe"      %% "circe-parser"     % "0.13.0",
  "io.circe"      %% "circe-yaml"       % "0.13.0",
  "org.scalameta" %% "scalafmt-dynamic" % "2.7.5"
)
