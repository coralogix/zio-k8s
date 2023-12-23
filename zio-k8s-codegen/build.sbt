sbtPlugin := true

organization := "com.coralogix"
name         := "zio-k8s-codegen"

scalaVersion := "2.12.18"

Compile / unmanagedSourceDirectories += baseDirectory.value / "src/shared/scala"

scalacOptions ++= Seq("-feature", "-deprecation")

libraryDependencies ++= Seq(
  "dev.zio"             %% "zio"              % "2.0.20",
  "dev.zio"             %% "zio-nio"          % "2.0.2",
  "io.swagger.parser.v3" % "swagger-parser"   % "2.1.14",
  "io.circe"            %% "circe-core"       % "0.14.6",
  "io.circe"            %% "circe-parser"     % "0.14.6",
  "io.circe"            %% "circe-yaml"       % "0.14.2",
  "org.scalameta"       %% "scalameta"        % "4.8.14",
  "org.scalameta"       %% "scalafmt-dynamic" % "3.7.2",
  "org.atteo"            % "evo-inflector"    % "1.3",
  "io.github.vigoo"     %% "metagen-core"     % "0.0.21"
)
