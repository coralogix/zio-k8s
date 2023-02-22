val scala212Version = "2.12.17"
val scala213Version = "2.13.10"
val scala3Version = "3.2.2"

val zioVersion = "2.0.9"
val zioConfigVersion = "3.0.7"
val zioLoggingVersion = "2.1.7"
val sttpVersion = "3.8.1"
val zioNioVersion = "2.0.1"
val zioPreludeVersion = "1.0.0-RC16"

Global / onChangedBuildSource := ReloadOnSourceChanges

inThisBuild(
  List(
    organization := "com.coralogix",
    homepage     := Some(url("https://github.com/coralogix/zio-k8s")),
    licenses     := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers   := List(
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
  crossScalaVersions := List(scala212Version, scala213Version, scala3Version),
  autoAPIMappings    := true,
  excludeDependencies ++=
    (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _)) =>
        Seq(
          ExclusionRule("org.scala-lang.modules", "scala-collection-compat_2.13")
        )
      case _            => Seq.empty[ExclusionRule]
    })
)

lazy val root = Project("zio-k8s", file("."))
  .settings(
    publish / skip := true
  )
  .aggregate(
    client,
    clientMonocle,
    clientQuicklens,
    clientZioOptics,
    crd,
    operator,
    examples
  )

lazy val client = Project("zio-k8s-client", file("zio-k8s-client"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"                       %% "zio"                           % zioVersion,
      "dev.zio"                       %% "zio-streams"                   % zioVersion,
      "dev.zio"                       %% "zio-config"                    % zioConfigVersion,
      "dev.zio"                       %% "zio-nio"                       % zioNioVersion,
      "dev.zio"                       %% "zio-process"                   % "0.7.1",
      "dev.zio"                       %% "zio-prelude"                   % zioPreludeVersion,
      "com.softwaremill.sttp.client3" %% "core"                          % sttpVersion,
      "com.softwaremill.sttp.client3" %% "zio"                           % sttpVersion,
      "com.softwaremill.sttp.client3" %% "circe"                         % sttpVersion,
      "io.circe"                      %% "circe-core"                    % "0.14.2",
      "io.circe"                      %% "circe-generic"                 % "0.14.2",
      "io.circe"                      %% "circe-parser"                  % "0.14.2",
      "io.circe"                      %% "circe-yaml"                    % "0.14.1",
      "org.bouncycastle"               % "bcpkix-jdk18on"                % "1.71",
      "dev.zio"                       %% "zio-test"                      % zioVersion       % Test,
      "dev.zio"                       %% "zio-test-sbt"                  % zioVersion       % Test,
      "dev.zio"                       %% "zio-config-typesafe"           % zioConfigVersion % Test,
      "com.softwaremill.sttp.client3" %% "slf4j-backend"                 % sttpVersion      % Optional,
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % sttpVersion      % Optional
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Compile / packageSrc / mappings ++= {
      val base = (Compile / sourceManaged).value
      val files = (Compile / managedSources).value
      files
        .map { f =>
          (f, f.relativeTo(base).map(_.getPath))
        }
        .collect { case (f, Some(g)) =>
          (f -> g)
        }
    },
    buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, isSnapshot),
    buildInfoPackage := "com.coralogix.zio.k8s",
    buildInfoObject  := "BuildInfo"
  )
  .enablePlugins(K8sResourceCodegenPlugin, BuildInfoPlugin)

lazy val clientQuicklens = Project("zio-k8s-client-quicklens", file("zio-k8s-client-quicklens"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.quicklens" %% "quicklens"    % "1.8.10",
      "dev.zio"                    %% "zio-test"     % zioVersion % Test,
      "dev.zio"                    %% "zio-test-sbt" % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
  .dependsOn(client)

lazy val clientMonocle = Project("zio-k8s-client-monocle", file("zio-k8s-client-monocle"))
  .settings(commonSettings)
  .settings(
    crossScalaVersions := List(scala212Version, scala213Version),
    Compile / scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) if n >= 13 => "-Ymacro-annotations" :: Nil
        case _                       => Nil
      }
    },
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) if n <= 12 =>
          List(compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full))
        case _                       => Nil
      }
    },
    libraryDependencies ++= Seq(
      "com.github.julien-truffaut" %% "monocle-core"  % "2.1.0",
      "com.github.julien-truffaut" %% "monocle-macro" % "2.1.0",
      "dev.zio"                    %% "zio-test"      % zioVersion % Test,
      "dev.zio"                    %% "zio-test-sbt"  % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Compile / packageSrc / mappings ++= {
      val base = (Compile / sourceManaged).value
      val files = (Compile / managedSources).value
      files
        .map { f =>
          (f, f.relativeTo(base).map(_.getPath))
        }
        .collect { case (f, Some(g)) =>
          (f -> g)
        }
    }
  )
  .dependsOn(client)
  .enablePlugins(K8sMonocleCodegenPlugin)

lazy val clientZioOptics = Project("zio-k8s-client-optics", file("zio-k8s-client-optics"))
  .settings(commonSettings)
  .settings(
    crossScalaVersions := List(scala212Version, scala213Version, scala3Version),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-optics"   % "0.2.0",
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Compile / packageSrc / mappings ++= {
      val base = (Compile / sourceManaged).value
      val files = (Compile / managedSources).value
      files
        .map { f =>
          (f, f.relativeTo(base).map(_.getPath))
        }
        .collect { case (f, Some(g)) =>
          (f -> g)
        }
    }
  )
  .dependsOn(client)
  .enablePlugins(K8sOpticsCodegenPlugin)

lazy val crd = Project("zio-k8s-crd", file("zio-k8s-crd"))
  .settings(commonSettings)
  .settings(
    sbtPlugin          := true,
    scalaVersion       := "2.12.16",
    crossVersion       := CrossVersion.disabled,
    Compile / unmanagedSourceDirectories += baseDirectory.value / "../zio-k8s-codegen/src/shared/scala",
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio"              % zioVersion,
      "dev.zio"       %% "zio-streams"      % zioVersion,
      "dev.zio"       %% "zio-nio"          % zioNioVersion,
      "com.twilio"    %% "guardrail"        % "0.64.1",
      "org.scalameta" %% "scalafmt-dynamic" % "2.7.5",
      "org.atteo"      % "evo-inflector"    % "1.3",
      "dev.zio"       %% "zio-test"         % zioVersion % Test,
      "dev.zio"       %% "zio-test-sbt"     % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    compile / skip     := {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 12)) => false
        case _             => true
      }
    },
    publish / skip     := {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 12)) => false
        case _             => true
      }
    },
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Xss2048k", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog  := false,
    publishLocal       := publishLocal.dependsOn(client / publishLocal).value
  )
  .dependsOn(client)
  .enablePlugins(SbtPlugin)

lazy val operator = Project("zio-k8s-operator", file("zio-k8s-operator"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"          % zioVersion,
      "dev.zio" %% "zio-streams"  % zioVersion,
      "dev.zio" %% "zio-nio"      % zioNioVersion,
      "dev.zio" %% "zio-test"     % zioVersion        % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion        % Test,
      "dev.zio" %% "zio-logging"  % zioLoggingVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
  .dependsOn(client)

lazy val examples = project
  .in(file("examples"))
  .settings(
    publish / skip := true
  )
  .aggregate(
    leaderExample,
    opticsExample,
    logsExample
  )

lazy val leaderExample = Project("leader-example", file("examples/leader-example"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"                       %% "zio-config-typesafe" % zioConfigVersion,
      "com.softwaremill.sttp.client3" %% "slf4j-backend"       % sttpVersion
    ),
    Docker / packageName := "leader-example",
    Docker / version     := "0.0.1",
    dockerBaseImage      := "openjdk:11",
    publish / skip       := true
  )
  .dependsOn(operator)
  .enablePlugins(JavaAppPackaging, DockerPlugin)

val opticsExample = Project("optics-example", file("examples/optics-example"))
  .settings(commonSettings)
  .settings(
    publish / skip := true
  )
  .dependsOn(client, clientQuicklens, clientMonocle)

val logsExample = Project("logs-example", file("examples/logs-example"))
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %% "slf4j-backend" % sttpVersion
    )
  )
  .dependsOn(client)

lazy val docs = project
  .in(file("zio-k8s-docs"))
  .settings(
    publish / skip                             := true,
    moduleName                                 := "zio-k8s-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-config-typesafe"    % zioConfigVersion,
      "dev.zio" %% "zio-metrics-prometheus" % "2.0.0"
    ),
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      client,
      clientMonocle,
      clientQuicklens,
      operator
    ),
    ScalaUnidoc / unidoc / target              := (LocalRootProject / baseDirectory).value / "website" / "static" / "api",
    cleanFiles += (ScalaUnidoc / unidoc / target).value,
    docusaurusCreateSite                       := docusaurusCreateSite.dependsOn(Compile / unidoc).value,
    docusaurusPublishGhpages                   := docusaurusPublishGhpages.dependsOn(Compile / unidoc).value
  )
  .dependsOn(client, clientQuicklens, clientMonocle, clientZioOptics, operator)
  .enablePlugins(MdocPlugin, DocusaurusPlugin, ScalaUnidocPlugin)
