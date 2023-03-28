sbtPlugin := true

organization := "com.coralogix"
name         := "zio-k8s-codegen"

scalaVersion := "2.12.17"

Compile / unmanagedSourceDirectories += baseDirectory.value / "src/shared/scala"

scalacOptions ++= Seq("-feature", "-deprecation")

libraryDependencies ++= Seq(
  "dev.zio"             %% "zio"              % "2.0.9",
  "dev.zio"             %% "zio-nio"          % "2.0.1",
  "io.swagger.parser.v3" % "swagger-parser"   % "2.0.24",
  "io.circe"            %% "circe-core"       % "0.14.2",
  "io.circe"            %% "circe-parser"     % "0.14.2",
  "io.circe"            %% "circe-yaml"       % "0.14.1",
  "org.scalameta"       %% "scalameta"        % "4.4.21",
  "org.scalameta"       %% "scalafmt-dynamic" % "2.7.5",
  "org.atteo"            % "evo-inflector"    % "1.3"
)
