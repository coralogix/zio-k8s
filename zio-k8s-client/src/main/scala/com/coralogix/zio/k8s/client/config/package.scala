package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.K8sCluster
import sttp.client3.UriContext
import sttp.model.Uri
import zio.blocking.Blocking
import zio.config._
import zio.config.magnolia.DeriveConfigDescriptor.Descriptor
import zio.nio.core.file.Path
import zio.system.System
import zio.{ system, Has, Task, ZIO, ZLayer, ZManaged }

import java.io.{ ByteArrayInputStream, FileInputStream, InputStream }
import java.nio.charset.StandardCharsets
import java.util.Base64

package object config extends Descriptors {
  sealed trait KeySource
  object KeySource {
    final case class FromFile(path: Path) extends KeySource
    final case class FromBase64(base64: String) extends KeySource
    final case class FromString(value: String) extends KeySource

    def from(maybePath: Option[String], maybeBase64: Option[String]): Either[String, KeySource] =
      (maybePath, maybeBase64) match {
        case (Some(path), None)   => Right(FromFile(Path(path)))
        case (None, Some(base64)) => Right(FromBase64(base64))
        case (None, None)         =>
          Left("Missing configuration, neither key path or key data is specified")
        case (Some(_), Some(_))   =>
          Left("Ambiguous configuration, both key path and key data is specified")
      }
  }

  sealed trait K8sAuthentication
  object K8sAuthentication {
    final case class ServiceAccountToken(token: KeySource) extends K8sAuthentication
    final case class BasicAuth(username: String, password: String) extends K8sAuthentication
    final case class ClientCertificates(
      certificate: KeySource,
      key: KeySource,
      password: Option[String]
    ) extends K8sAuthentication
  }

  sealed trait K8sServerCertificate
  object K8sServerCertificate {
    case object Insecure extends K8sServerCertificate
    final case class Secure(certificate: KeySource, disableHostnameVerification: Boolean)
        extends K8sServerCertificate
  }

  case class K8sClientConfig(
    debug: Boolean,
    serverCertificate: K8sServerCertificate
  )

  case class K8sClusterConfig(
    host: Uri,
    authentication: K8sAuthentication,
    client: K8sClientConfig
  ) {
    def dropTrailingDot: K8sClusterConfig =
      this.host.host match {
        case Some(host) =>
          this.copy(host = this.host.host(host.stripSuffix(".")))
        case None       =>
          this
      }
  }

  object K8sClusterConfig {
    implicit val k8sClusterConfigDescriptor: Descriptor[K8sClusterConfig] =
      Descriptor(clusterConfigDescriptor)
  }

  /** Layer producing a [[K8sCluster]] from a provided K8sClusterConfig
    *
    * This can be used to either set up from a configuration source with zio-config or
    * provide the hostname and token programmatically for the Kubernetes client.
    */
  val k8sCluster: ZLayer[Blocking with Has[K8sClusterConfig], Throwable, Has[K8sCluster]] =
    (for {
      config <- getConfig[K8sClusterConfig]
      result <- config.authentication match {
                  case K8sAuthentication.ServiceAccountToken(tokenSource) =>
                    loadKeyString(tokenSource).use { token =>
                      ZIO.succeed(K8sCluster(config.host, Some(_.auth.bearer(token))))
                    }
                  case K8sAuthentication.BasicAuth(username, password)    =>
                    ZIO.succeed(K8sCluster(config.host, Some(_.auth.basic(username, password))))
                  case K8sAuthentication.ClientCertificates(_, _, _)      =>
                    ZIO.succeed(K8sCluster(config.host, None))
                }
    } yield result).toLayer

  /** Layer producing a [[K8sClusterConfig]] that first tries to load a kubeconfig and
    * if it cannot find one fallbacks to using the default service account token.
    *
    * For more customization see [[kubeconfig()]] and [[serviceAccount()]] or provide
    * a [[K8sClusterConfig]] manually.
    */
  val defaultConfigChain: ZLayer[System with Blocking, Throwable, Has[K8sClusterConfig]] =
    ((System.any ++ Blocking.any ++ findKubeconfigFile().some.toLayer) >>> kubeconfigFrom())
      .orElse(serviceAccount())

  /** Layer producing a [[K8sClusterConfig]] using the default service account when running
    * from inside a pod.
    *
    * @param debug Enable debug request/response logging
    */
  def serviceAccount(debug: Boolean = false): ZLayer[Any, Nothing, Has[K8sClusterConfig]] =
    ZLayer.succeed(
      K8sClusterConfig(
        host = uri"https://kubernetes.default.svc",
        authentication = K8sAuthentication.ServiceAccountToken(
          KeySource.FromFile(
            Path("/var/run/secrets/kubernetes.io/serviceaccount/token")
          )
        ),
        K8sClientConfig(
          debug,
          K8sServerCertificate.Secure(
            certificate = KeySource.FromFile(
              Path("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
            ),
            disableHostnameVerification = false
          )
        )
      )
    )

  /** Layer producing a [[K8sClusterConfig]] by loading a kubeconfig file
    *
    * If the KUBECONFIG environment variable is set, that will be used as the kubeconfig file's path,
    * otherwise ~/.kube/config based on the current user's home directory.
    *
    * To use a specific kubeconfig file path, use [[kubeconfigFile]].
    *
    * @param context Override the current context in the configuration file and use another one
    * @param debug Enable debug request/response logging
    * @param disableHostnameVerification Disables hostname verification on the SSL connection
    */
  def kubeconfig(
    context: Option[String] = None,
    debug: Boolean = false,
    disableHostnameVerification: Boolean = false
  ): ZLayer[Blocking with System, Throwable, Has[K8sClusterConfig]] =
    (for {
      maybePath <- findKubeconfigFile()
      path      <- maybePath match {
                     case Some(path) => ZIO.succeed(path)
                     case None       =>
                       ZIO.fail(
                         new IllegalStateException(
                           s"Neither KUBECONFIG nor the user's home directory is known"
                         )
                       )
                   }
      config    <- fromKubeconfigFile(path, context, debug, disableHostnameVerification)
    } yield config).toLayer

  private def kubeconfigFrom(
    context: Option[String] = None,
    debug: Boolean = false,
    disableHostnameVerification: Boolean = false
  ): ZLayer[Blocking with System with Has[Path], Throwable, Has[K8sClusterConfig]] =
    (for {
      path   <- ZIO.service[Path]
      config <- fromKubeconfigFile(path, context, debug, disableHostnameVerification)
    } yield config).toLayer

  /** Layer setting up a [[K8sCluster]] by loading a specific kubeconfig file
    * @param configPath Path to the kubeconfig file to load
    * @param context Override the current context in the configuration file and use another one
    * @param debug Enable debug request/response logging
    * @param disableHostnameVerification Disables hostname verification on the SSL connection
    */
  def kubeconfigFile(
    configPath: Path,
    context: Option[String] = None,
    debug: Boolean = false,
    disableHostnameVerification: Boolean = false
  ): ZLayer[Blocking, Throwable, Has[K8sClusterConfig]] =
    fromKubeconfigFile(configPath, context, debug, disableHostnameVerification).toLayer

  private def findKubeconfigFile(): ZIO[Blocking with System, Throwable, Option[Path]] =
    for {
      envVar <- system.env("KUBECONFIG")
      home   <- system.property("user.home")
      path    = (envVar, home) match {
                  case (Some(path), _)    => Some(Path(path))
                  case (None, Some(home)) => Some(Path(home) / ".kube/config")
                  case _                  => None
                }
    } yield path

  private def fromKubeconfigFile(
    configPath: Path,
    context: Option[String],
    debug: Boolean,
    disableHostnameVerification: Boolean
  ): ZIO[Blocking, Throwable, K8sClusterConfig] =
    for {
      kubeconfig      <- Kubeconfig.load(configPath)
      maybeContextInfo = context match {
                           case Some(forcedContext) =>
                             kubeconfig.contextMap.get(forcedContext)
                           case None                =>
                             kubeconfig.currentContext
                         }
      contextInfo     <-
        ZIO
          .fromOption(maybeContextInfo)
          .orElseFail(
            new RuntimeException(
              s"Could not find context ${context.getOrElse(kubeconfig.`current-context`)} in kubeconfig $configPath"
            )
          )
      cluster         <- ZIO
                           .fromOption(kubeconfig.clusterMap.get(contextInfo.cluster))
                           .orElseFail(
                             new RuntimeException(
                               s"Could not find cluster ${contextInfo.cluster} in kubeconfig $configPath"
                             )
                           )
      user            <- ZIO
                           .fromOption(kubeconfig.userMap.get(contextInfo.user))
                           .orElseFail(
                             new RuntimeException(
                               s"Could not find user ${contextInfo.user} in kubeconfig $configPath"
                             )
                           )
      host            <- ZIO
                           .fromEither(Uri.parse(cluster.server))
                           .mapError(s => new RuntimeException(s"Failed to parse host URL: $s"))
      authentication  <- userInfoToAuthentication(user)
      serverCert      <-
        ZIO
          .fromEither(
            KeySource.from(cluster.`certificate-authority`, cluster.`certificate-authority-data`)
          )
          .mapError(new RuntimeException(_))
      client           =
        K8sClientConfig(debug, K8sServerCertificate.Secure(serverCert, disableHostnameVerification))
    } yield K8sClusterConfig(
      host,
      authentication,
      client
    )

  private def userInfoToAuthentication(user: KubeconfigUserInfo): Task[K8sAuthentication] =
    (user.token, user.username) match {
      case (Some(token), None)    =>
        ZIO.succeed(K8sAuthentication.ServiceAccountToken(KeySource.FromString(token)))
      case (None, Some(username)) =>
        user.password match {
          case Some(password) =>
            ZIO.succeed(K8sAuthentication.BasicAuth(username, password))
          case None           =>
            ZIO.fail(new RuntimeException("Username without password in kubeconfig"))
        }
      case (Some(_), Some(_))     =>
        ZIO.fail(new RuntimeException("Both token and username is provided in kubeconfig"))
      case (None, None)           =>
        for {
          clientCert <-
            ZIO
              .fromEither(KeySource.from(user.`client-certificate`, user.`client-certificate-data`))
              .mapError(new RuntimeException(_))
          clientKey  <- ZIO
                          .fromEither(KeySource.from(user.`client-key`, user.`client-key-data`))
                          .mapError(new RuntimeException(_))
        } yield K8sAuthentication.ClientCertificates(clientCert, clientKey, None)
    }

  private[config] def loadKeyStream(source: KeySource): ZManaged[Any, Throwable, InputStream] =
    ZManaged.fromAutoCloseable {
      source match {
        case KeySource.FromFile(path)     =>
          Task.effect(new FileInputStream(path.toFile))
        case KeySource.FromBase64(base64) =>
          Task.effect(new ByteArrayInputStream(Base64.getDecoder.decode(base64)))
        case KeySource.FromString(value)  =>
          Task.effect(new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII)))
      }
    }

  private def loadKeyString(source: KeySource): ZManaged[Any, Throwable, String] =
    source match {
      case KeySource.FromFile(path)     =>
        ZManaged
          .fromAutoCloseable(Task.effect(new FileInputStream(path.toFile)))
          .flatMap { stream =>
            ZManaged.fromEffect(Task(new String(stream.readAllBytes(), StandardCharsets.US_ASCII)))
          }
      case KeySource.FromBase64(base64) =>
        ZManaged.fromEffect(
          Task(new String(Base64.getDecoder.decode(base64), StandardCharsets.US_ASCII))
        )
      case KeySource.FromString(value)  =>
        ZManaged.succeed(value)
    }
}
