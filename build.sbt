val scala212Version = "2.12.12"
val scala213Version = "2.13.4"
// val scala3Version = "3.0.0-M3"

val zioVersion = "1.0.3"

inThisBuild(
  List(
    organization := "com.coralogix",
    homepage     := Some(url("https://github.com/coralogix/zio-k8s")),
    licenses     := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "vigoo",
        "Daniel Vigovszky",
        "daniel.vigovszky@gmail.com",
        url("https://www.coralogix.com")
      )
    )
  )
)

val commonSettings = Seq(
  organization       := "com.coralogix",
  scalaVersion       := scala212Version,
  crossScalaVersions := List(scala212Version, scala213Version)
)

lazy val root = Project("zio-k8s", file("."))
  .settings(
    publish / skip := true
  )
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
      "dev.zio"                       %% "zio-config"             % "1.0.0-RC30-1",
      "dev.zio"                       %% "zio-config-magnolia"    % "1.0.0-RC30-1",
      "dev.zio"                       %% "zio-logging"            % "0.5.4",
      "com.softwaremill.sttp.client3" %% "httpclient-backend-zio" % "3.0.0-RC15",
      "com.softwaremill.sttp.client3" %% "slf4j-backend"          % "3.0.0-RC15",
      "com.softwaremill.sttp.client3" %% "circe"                  % "3.0.0-RC15",
      "io.circe"                      %% "circe-core"             % "0.13.0",
      "io.circe"                      %% "circe-parser"           % "0.13.0",
      "io.circe"                      %% "circe-yaml"             % "0.13.1",
      "dev.zio"                       %% "zio-test"               % zioVersion % Test,
      "dev.zio"                       %% "zio-test-sbt"           % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

    mappings in (Compile, packageSrc) ++= {
      val base = (sourceManaged in Compile).value
      val files = (managedSources in Compile).value
      files.map { f =>
        (f, f.relativeTo(base).map(_.getPath))
      }.collect {
        case (f, Some(g)) => (f -> g)
      }
    },
  )
  .enablePlugins(K8sResourceCodegenPlugin)

lazy val crd = Project("zio-k8s-crd", file("zio-k8s-crd"))
  .settings(commonSettings)
  .settings(
    sbtPlugin    := true,
    scalaVersion := "2.12.12",
    crossVersion := CrossVersion.disabled,
    Compile / unmanagedSourceDirectories += baseDirectory.value / "../zio-k8s-codegen/src/shared/scala",
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio"              % zioVersion,
      "dev.zio"       %% "zio-streams"      % zioVersion,
      "com.twilio"    %% "guardrail"        % "0.61.0",
      "org.scalameta" %% "scalafmt-dynamic" % "2.7.5",
      "dev.zio"       %% "zio-test"         % zioVersion % Test,
      "dev.zio"       %% "zio-test-sbt"     % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    compile / skip := {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 12)) => false
        case _             => true
      }
    },
    publish / skip := {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 12)) => false
        case _             => true
      }
    }
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
