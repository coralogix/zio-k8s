val scala212Version = "2.12.12"
val scala213Version = "2.13.4"
// val scala3Version = "3.0.0-M3"

val zioVersion = "1.0.3"

val commonSettings = Seq(
  organization       := "com.coralogix",
  version            := "0.1",
  scalaVersion       := scala212Version,
  crossScalaVersions := List(scala212Version, scala213Version)
)

lazy val root = Project("zio-k8s", file("."))
  .aggregate(
    client,
    crd,
    operator
  )

lazy val client = Project("zio-k8s-client", file("zio-k8s-client"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"                       %% "zio"                    % zioVersion,
      "dev.zio"                       %% "zio-streams"            % zioVersion,
      "dev.zio"                       %% "zio-config"             % "1.0.0-RC31-1",
      "dev.zio"                       %% "zio-config-magnolia"    % "1.0.0-RC31-1",
      "dev.zio"                       %% "zio-logging"            % "0.5.3",
      "com.softwaremill.sttp.client3" %% "httpclient-backend-zio" % "3.0.0-RC10",
      "com.softwaremill.sttp.client3" %% "slf4j-backend"          % "3.0.0-RC10",
      "com.softwaremill.sttp.client3" %% "circe"                  % "3.0.0-RC10",
      "io.circe"                      %% "circe-core"             % "0.13.0",
      "io.circe"                      %% "circe-parser"           % "0.13.0",
      "io.circe"                      %% "circe-yaml"             % "0.13.0",
      "dev.zio"                       %% "zio-test"               % zioVersion % Test,
      "dev.zio"                       %% "zio-test-sbt"           % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
  .enablePlugins(K8sResourceCodegenPlugin)

lazy val crd = Project("zio-k8s-crd", file("zio-k8s-crd"))
  .settings(commonSettings)
  .settings(
    sbtPlugin    := true,
    scalaVersion := "2.12.12",
    Compile / unmanagedSourceDirectories += baseDirectory.value / "../zio-k8s-codegen/src/shared/scala",
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio"              % zioVersion,
      "dev.zio"       %% "zio-streams"      % zioVersion,
      "com.twilio"    %% "guardrail"        % "0.61.0",
      "org.scalameta" %% "scalafmt-dynamic" % "2.7.5",
      "dev.zio"       %% "zio-test"         % zioVersion % Test,
      "dev.zio"       %% "zio-test-sbt"     % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
  .dependsOn(client)

lazy val operator = Project("zio-k8s-operator", file("zio-k8s-operator"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"          % zioVersion,
      "dev.zio" %% "zio-streams"  % zioVersion,
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
  .dependsOn(client)
