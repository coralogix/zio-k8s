package com.coralogix.zio.k8s.client.config

import io.circe._
import io.circe.generic.semiauto._
import io.circe.yaml.parser._

import zio.nio.file.Path
import zio.nio.file.Files
import zio.{ IO, Task, ZIO }

import java.nio.charset.StandardCharsets

case class KubeconfigClusterInfo(
  server: String,
  `certificate-authority`: Option[String],
  `certificate-authority-data`: Option[String]
)

object KubeconfigClusterInfo {
  implicit val codec: Codec[KubeconfigClusterInfo] = deriveCodec
}

case class KubeconfigCluster(name: String, cluster: KubeconfigClusterInfo)

object KubeconfigCluster {
  implicit val codec: Codec[KubeconfigCluster] = deriveCodec
}

case class KubeconfigContextInfo(cluster: String, user: String, namespace: Option[String])

object KubeconfigContextInfo {
  implicit val codec: Codec[KubeconfigContextInfo] = deriveCodec
}

case class KubeconfigContext(name: String, context: KubeconfigContextInfo)

object KubeconfigContext {
  implicit val codec: Codec[KubeconfigContext] = deriveCodec
}

final case class ExecEnv(name: String, value: String)

object ExecEnv {
  implicit val codec: Codec[ExecEnv] = deriveCodec
}

final case class ExecConfig(
  apiVersion: String,
  command: String,
  env: Option[Set[ExecEnv]],
  args: Option[List[String]],
  installHint: Option[String],
  provideClusterInfo: Option[Boolean]
)

object ExecConfig {
  implicit val codec: Codec[ExecConfig] = deriveCodec
}

case class KubeconfigUserInfo(
  `client-certificate`: Option[String],
  `client-certificate-data`: Option[String],
  `client-key`: Option[String],
  `client-key-data`: Option[String],
  token: Option[String],
  username: Option[String],
  password: Option[String],
  exec: Option[ExecConfig]
)

object KubeconfigUserInfo {
  implicit val codec: Codec[KubeconfigUserInfo] = deriveCodec
}

case class KubeconfigUser(name: String, user: KubeconfigUserInfo)

object KubeconfigUser {
  implicit val codec: Codec[KubeconfigUser] = deriveCodec
}

case class Kubeconfig(
  clusters: List[KubeconfigCluster],
  contexts: List[KubeconfigContext],
  users: List[KubeconfigUser],
  `current-context`: String
) {
  lazy val clusterMap: Map[String, KubeconfigClusterInfo] =
    clusters.map(item => item.name -> item.cluster).toMap
  lazy val contextMap: Map[String, KubeconfigContextInfo] =
    contexts.map(item => item.name -> item.context).toMap
  lazy val userMap: Map[String, KubeconfigUserInfo] =
    users.map(item => item.name -> item.user).toMap

  def currentContext: Option[KubeconfigContextInfo] =
    contextMap.get(`current-context`)
}

object Kubeconfig {
  implicit val codec: Codec[Kubeconfig] = deriveCodec

  def load(configPath: Path): ZIO[Any, Throwable, Kubeconfig] =
    for {
      yamlBytes  <- Files.readAllBytes(configPath)
      yamlString <- Task(new String(yamlBytes.toArray, StandardCharsets.UTF_8))
      kubeconfig <- loadFromString(yamlString)
    } yield kubeconfig

  /** Supply the contents of the kubeconfig directly as a String. The contents of the `configString`
    * can be either yaml or json.
    * @param configString
    *   yaml/json kubeconfig file contents
    * @return
    */
  def loadFromString(configString: String): IO[Throwable, Kubeconfig] =
    for {
      yaml       <- ZIO.fromEither(parse(configString))
      kubeconfig <- ZIO.fromEither(yaml.as[Kubeconfig])
    } yield kubeconfig
}
