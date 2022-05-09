package com.coralogix.zio.k8s.client

import cats.implicits._
import com.coralogix.zio.k8s.client.model.K8sCluster
import io.circe.generic.semiauto.deriveDecoder
import io.circe.{ parser, Decoder }
import sttp.client3.UriContext
import sttp.model.Uri
import zio.config._
import zio.nio.file.Path
import zio.process.Command
import zio.{ Layer, RIO, System, Task, ZIO, ZLayer }

import java.io.{ ByteArrayInputStream, File, FileInputStream, InputStream }
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Contains data structures, ZIO layers and zio-config descriptors for configuring the zio-k8s
  * client.
  *
  * Each zio-k8s client module depends on two ZIO modules:
  * [[com.coralogix.zio.k8s.client.model.K8sCluster]] and an [[sttp.client3.SttpBackend]]. To use
  * the default configuration (use kubeconfig if available, otherwise fallback to service account
  * token), use either `asynchttpclient.k8sDefault` or `httpclient.k8sDefault` depending on your
  * chosen sttp backend.
  *
  * Manual configuration is possible by providing a [[K8sClusterConfig]] value to both the
  * [[k8sCluster]] layer and either `asynchttpclient.k8sSttpClient` or `httpclient.k8sSttpClient`.
  *
  * Instead of manually providing the configuration, zio-config descriptors are available to load
  * them from any supported source.
  */
package object config extends Descriptors {

  /** Abstraction for configuring keys
    */
  sealed trait KeySource
  object KeySource {

    /** Key loaded from an external file
      * @param path
      *   path of the file
      */
    final case class FromFile(path: Path) extends KeySource

    /** Key loaded from a Base64 string
      * @param base64
      *   base64 encoded key value
      */
    final case class FromBase64(base64: String) extends KeySource

    /** Key loaded from a raw string
      * @param value
      *   key as a simple string
      */
    final case class FromString(value: String) extends KeySource

    /** Defines a key source from either an external file path or a base64 encoded value.
      *
      * If neither or both are provided the result is an error.
      * @param maybePath
      *   Path to the key file if any
      * @param maybeBase64
      *   Base64 encoded key value
      */
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

  /** Type of authentication to use with the Kubernetes cluster
    */
  sealed trait K8sAuthentication
  object K8sAuthentication {

    /** Authenticate with a service account token
      *
      * See
      * https://kubernetes.io/docs/reference/access-authn-authz/authentication/#service-account-tokens
      * @param token
      *   The key source must point to a PEM encoded bearer token file, or a raw bearer token value.
      */
    final case class ServiceAccountToken(token: KeySource) extends K8sAuthentication

    /** Authenticate with basic authentication
      *
      * @param username
      *   Username for basic authentication
      * @param password
      *   Password for basic authentication
      */
    final case class BasicAuth(username: String, password: String) extends K8sAuthentication

    /** Authenticate with X509 client certificates
      *
      * See
      * https://kubernetes.io/docs/reference/access-authn-authz/authentication/#x509-client-certs
      *
      * @param certificate
      *   Client certificate
      * @param key
      *   Client's private key
      * @param password
      *   Passphrase for the key if needed
      */
    final case class ClientCertificates(
      certificate: KeySource,
      key: KeySource,
      password: Option[String]
    ) extends K8sAuthentication
  }

  /** Configured Kubernetes server certifications
    *
    * [[K8sServerCertificate.Insecure]] should only be used for testing purposes.
    */
  sealed trait K8sServerCertificate
  object K8sServerCertificate {

    /** Insecure connection
      */
    case object Insecure extends K8sServerCertificate

    /** Secure TLS connection
      * @param certificate
      *   Server certification
      * @param disableHostnameVerification
      *   Disables hostname verification
      */
    final case class Secure(certificate: KeySource, disableHostnameVerification: Boolean)
        extends K8sServerCertificate
  }

  /** Configuration for the HTTP connection towards the Kubernetes API
    * @param debug
    *   Enables detailed debug logging
    * @param serverCertificate
    *   The server certificate to use
    */
  case class K8sClientConfig(
    debug: Boolean,
    serverCertificate: K8sServerCertificate
  )

  /** Configures the zio-k8s client
    *
    * This is the top level configuration class.
    *
    * @param host
    *   URL of the Kubernetes API
    * @param authentication
    *   Authentication method to use
    * @param client
    *   HTTP client configuration
    */
  case class K8sClusterConfig(
    host: Uri,
    authentication: K8sAuthentication,
    client: K8sClientConfig
  ) {

    /** Drops the trailing dot from the configured host name.
      *
      * This is a workaround for an issue when the kubeconfig file contains hostnames with trailing
      * dots which is not supported by the hostname verification algorithm. Use this together with
      * the [[K8sServerCertificate.Secure.disableHostnameVerification]] option.
      */
    def dropTrailingDot: K8sClusterConfig =
      this.host.host match {
        case Some(host) =>
          this.copy(host = this.host.host(host.stripSuffix(".")))
        case None       =>
          this
      }
  }

  /** Layer producing a [[com.coralogix.zio.k8s.client.model.K8sCluster]] from a provided
    * K8sClusterConfig
    *
    * This can be used to either set up from a configuration source with zio-config or provide the
    * hostname and token programmatically for the Kubernetes client.
    */
  val k8sCluster: ZLayer[K8sClusterConfig, Throwable, K8sCluster] =
    ZLayer.scoped {
      for {
        config <- getConfig[K8sClusterConfig]
        result <- config.authentication match {
                    case K8sAuthentication.ServiceAccountToken(tokenSource) =>
                      loadKeyString(tokenSource).flatMap { token =>
                        ZIO.succeed(K8sCluster(config.host, Some(_.auth.bearer(token))))
                      }
                    case K8sAuthentication.BasicAuth(username, password)    =>
                      ZIO.succeed(K8sCluster(config.host, Some(_.auth.basic(username, password))))
                    case K8sAuthentication.ClientCertificates(_, _, _)      =>
                      ZIO.succeed(K8sCluster(config.host, None))
                  }
      } yield result
    }

  /** Layer producing a [[K8sClusterConfig]] that first tries to load a kubeconfig and if it cannot
    * find one fallbacks to using the default service account token.
    *
    * For more customization see [[kubeconfig]] and [[serviceAccount]] or provide a
    * [[K8sClusterConfig]] manually.
    */
  val defaultConfigChain: ZLayer[Any, Throwable, K8sClusterConfig] =
    (ZLayer.fromZIO(findKubeconfigFile().some) >>> kubeconfigFrom())
      .orElse(serviceAccount())

  /** Layer producing a [[K8sClusterConfig]] using the default service account when running from
    * inside a pod.
    *
    * @param debug
    *   Enable debug request/response logging
    */
  def serviceAccount(debug: Boolean = false): ZLayer[Any, Nothing, K8sClusterConfig] =
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
    * If the KUBECONFIG environment variable is set, that will be used as the kubeconfig file's
    * path, otherwise ~/.kube/config based on the current user's home directory.
    *
    * To use a specific kubeconfig file path, use [[kubeconfigFile]].
    *
    * @param context
    *   Override the current context in the configuration file and use another one
    * @param debug
    *   Enable debug request/response logging
    * @param disableHostnameVerification
    *   Disables hostname verification on the SSL connection
    */
  def kubeconfig(
    context: Option[String] = None,
    debug: Boolean = false,
    disableHostnameVerification: Boolean = false
  ): Layer[Throwable, K8sClusterConfig] =
    ZLayer.fromZIO {
      for {
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
      } yield config
    }

  private def kubeconfigFrom(
    context: Option[String] = None,
    debug: Boolean = false,
    disableHostnameVerification: Boolean = false
  ): ZLayer[Any with Path, Throwable, K8sClusterConfig] =
    ZLayer {
      for {
        path   <- ZIO.service[Path]
        config <- fromKubeconfigFile(path, context, debug, disableHostnameVerification)
      } yield config
    }

  /** Layer setting up a [[com.coralogix.zio.k8s.client.model.K8sCluster]] by loading a specific
    * kubeconfig file
    * @param configPath
    *   Path to the kubeconfig file to load
    * @param context
    *   Override the current context in the configuration file and use another one
    * @param debug
    *   Enable debug request/response logging
    * @param disableHostnameVerification
    *   Disables hostname verification on the SSL connection
    */
  def kubeconfigFile(
    configPath: Path,
    context: Option[String] = None,
    debug: Boolean = false,
    disableHostnameVerification: Boolean = false
  ): ZLayer[Any, Throwable, K8sClusterConfig] =
    ZLayer.fromZIO(fromKubeconfigFile(configPath, context, debug, disableHostnameVerification))

  private def findKubeconfigFile(): ZIO[Any, Throwable, Option[Path]] =
    for {
      envVar <- System.env("KUBECONFIG")
      home   <- System.property("user.home")
      path    = (envVar, home) match {
                  case (Some(path), _)    => Some(Path(path))
                  case (None, Some(home)) => Some(Path(home) / ".kube/config")
                  case _                  => None
                }
    } yield path

  def kubeconfigFromString(
    configString: String,
    context: Option[String] = None,
    debug: Boolean = false,
    disableHostnameVerification: Boolean = false
  ): ZIO[Any, Throwable, K8sClusterConfig] =
    for {
      kubeconfig       <- Kubeconfig.loadFromString(configString)
      k8sClusterConfig <-
        fromKubeconfig(kubeconfig, None, context, debug, disableHostnameVerification)
    } yield k8sClusterConfig

  private def fromKubeconfigFile(
    configPath: Path,
    context: Option[String],
    debug: Boolean,
    disableHostnameVerification: Boolean
  ): ZIO[Any, Throwable, K8sClusterConfig] =
    for {
      kubeconfig       <- Kubeconfig.load(configPath)
      k8sClusterConfig <-
        fromKubeconfig(kubeconfig, Some(configPath), context, debug, disableHostnameVerification)
    } yield k8sClusterConfig

  def fromKubeconfig(
    kubeconfig: Kubeconfig,
    configPath: Option[Path],
    context: Option[String],
    debug: Boolean,
    disableHostnameVerification: Boolean
  ): ZIO[Any, Throwable, K8sClusterConfig] = {

    val maybeContextInfo = context match {
      case Some(forcedContext) =>
        kubeconfig.contextMap.get(forcedContext)
      case None                =>
        kubeconfig.currentContext
    }

    for {
      contextInfo    <-
        ZIO
          .fromOption(maybeContextInfo)
          .orElseFail(
            new RuntimeException(
              s"Could not find context ${context.getOrElse(kubeconfig.`current-context`)} in kubeconfig"
            )
          )
      cluster        <- ZIO
                          .fromOption(kubeconfig.clusterMap.get(contextInfo.cluster))
                          .orElseFail(
                            new RuntimeException(
                              s"Could not find cluster ${contextInfo.cluster} in kubeconfig"
                            )
                          )
      user           <- ZIO
                          .fromOption(kubeconfig.userMap.get(contextInfo.user))
                          .orElseFail(
                            new RuntimeException(
                              s"Could not find user ${contextInfo.user} in kubeconfig"
                            )
                          )
      host           <- ZIO
                          .fromEither(Uri.parse(cluster.server))
                          .mapError(s => new RuntimeException(s"Failed to parse host URL: $s"))
      authentication <- userInfoToAuthentication(user, configPath)
      serverCert     <-
        ZIO
          .fromEither(
            KeySource.from(cluster.`certificate-authority`, cluster.`certificate-authority-data`)
          )
          .mapError(new RuntimeException(_))
      client          =
        K8sClientConfig(debug, K8sServerCertificate.Secure(serverCert, disableHostnameVerification))
    } yield K8sClusterConfig(host, authentication, client)
  }

  final case class ExecCredentials(
    kind: String,
    apiVersion: String,
    status: ExecCredentialStatus
  )

  final case class ExecCredentialStatus(
    token: Option[String],
    certificate: Option[K8sAuthentication.ClientCertificates]
  )

  object ExecCredentials {
    private implicit val keyManagerDecoder: Decoder[Option[K8sAuthentication.ClientCertificates]] =
      Decoder.instance { c =>
        for {
          maybeCert <-
            c.get[Option[String]]("clientCertificateData").map(_.map(KeySource.FromString))
          maybeKey  <- c.get[Option[String]]("clientKeyData").map(_.map(KeySource.FromString))
        } yield (maybeCert, maybeKey) match {
          case (Some(cert), Some(key)) =>
            K8sAuthentication.ClientCertificates(cert, key, password = None).some
          case _                       => None
        }
      }
    implicit val execCredentialStatusDecoder: Decoder[ExecCredentialStatus] = Decoder.instance {
      c =>
        for {
          token <- c.get[Option[String]]("token")
          cert  <- c.as[Option[K8sAuthentication.ClientCertificates]]
        } yield ExecCredentialStatus(token, cert)
    }
    implicit val execCredentialsDecoder: Decoder[ExecCredentials] = deriveDecoder
  }

  private[config] val supportedClientAuthAPIVersions = Set(
    "client.authentication.k8s.io/v1alpha1",
    "client.authentication.k8s.io/v1beta1",
    "client.authentication.k8s.io/v1"
  )

  /** Execute a command to exchange credentials with an external serivce for a token this token is
    * then used as a bearer token against the API server
    * @param execConfig
    *   a command with optional arguments, optional env vars, command hint
    * @param configPath
    *   The kubeconfig path. Relative command paths are interpreted as relative to the directory of
    *   the config file. For example, If KUBECONFIG is set to /home/danny/kubeconfig and the exec
    *   command is ./bin/exec-plugin the binary /home/danny/bin/exec-plugin is executed.
    *
    * The configPath is optional because the config itself might not have been loaded from the
    * filesystem if Kubeconfig.loadFromString or kubeConfigFromString functions were called.
    * @return
    */
  private def runUserExecConfig(
    execConfig: ExecConfig,
    configPath: Option[Path]
  ): RIO[Any, K8sAuthentication] = {
    val prepareCommand = {
      val useRelativeCmdPath =
        execConfig.command.contains(File.separator) && !Path(execConfig.command).isAbsolute

      configPath match {
        case Some(configPath) if useRelativeCmdPath =>
          Task.attempt(configPath.resolveSibling(Path(execConfig.command)).normalize.toString)
        case _                                      => Task.attempt(execConfig.command)
      }
    }

    def runCommand(command: String) =
      Command(
        processName = command,
        execConfig.args.getOrElse(List.empty[String]): _*
      )
        .env(execConfig.env.getOrElse(Set.empty[ExecEnv]).map(x => x.name -> x.value).toMap)
        .string
        .mapError(error =>
          new RuntimeException(execConfig.installHint.getOrElse(""), error.getCause)
        )

    def decodeCommandResult(execResult: String) =
      ZIO
        .fromEither(parser.decode[ExecCredentials](execResult))
        .mapError(_.getCause)

    def validateCredsApiVersionSupported(execCredentials: ExecCredentials) =
      ZIO.when(!supportedClientAuthAPIVersions.contains(execCredentials.apiVersion))(
        ZIO.fail(
          new RuntimeException(
            s"Unsupported client.authentication api version: ${execCredentials.apiVersion}"
          )
        )
      )

    def validateCredentialsApi(execCredentials: ExecCredentials, execConfig: ExecConfig) =
      ZIO.when(execCredentials.apiVersion != execConfig.apiVersion) {
        ZIO.fail(
          new RuntimeException(
            s"Credentials api version: ${execCredentials.apiVersion} doesn't match exec config api version: ${execConfig.apiVersion}"
          )
        )
      }

    for {
      command         <- prepareCommand
      commandResult   <- runCommand(command)
      execCredentials <- decodeCommandResult(commandResult)
      _               <- validateCredsApiVersionSupported(execCredentials)
      _               <- validateCredentialsApi(execCredentials, execConfig)
      auth            <-
        execCredentials.status.token
          .map(x => K8sAuthentication.ServiceAccountToken(KeySource.FromString(x)))
          .orElse(execCredentials.status.certificate)
          .fold[Task[K8sAuthentication]](
            ZIO.fail(new RuntimeException("Neither token nor certificate returned by command"))
          )(ZIO.succeed(_))
    } yield auth
  }

  private def authenticationFromPlugin(
    user: KubeconfigUserInfo,
    configPath: Option[Path]
  ): RIO[Any, Option[K8sAuthentication]] =
    user.exec match {
      case Some(exec) => runUserExecConfig(exec, configPath).map(_.some)
      case _          => ZIO.none
    }

  private def userInfoToAuthentication(
    user: KubeconfigUserInfo,
    configPath: Option[Path]
  ): RIO[Any, K8sAuthentication] = {
    val token =
      user.token.map(token => K8sAuthentication.ServiceAccountToken(KeySource.FromString(token)))
    val usernamePassword: ZIO[Any, Throwable, Option[K8sAuthentication.BasicAuth]] =
      (user.username, user.password) match {
        case (Some(username), Some(password)) =>
          ZIO.some(K8sAuthentication.BasicAuth(username, password))

        case (None, None) =>
          ZIO.none

        case _ =>
          ZIO.fail(new RuntimeException("Both username and password should be provided"))
      }
    val clientCertificate = (for {
      cert <- KeySource.from(user.`client-certificate`, user.`client-certificate-data`)
      key  <- KeySource.from(user.`client-key`, user.`client-key-data`)
    } yield K8sAuthentication.ClientCertificates(cert, key, None)).toOption

    for {
      authFromPlugin <- authenticationFromPlugin(user, configPath)
      basicAuth      <- usernamePassword
      auth           <- List(authFromPlugin, token, basicAuth, clientCertificate).filter(_.isDefined) match {
                          case Nil            =>
                            ZIO.fail(new RuntimeException("No valid authentication is configured"))
                          case Some(x) :: Nil =>
                            ZIO.succeed(x)
                          case xs             =>
                            ZIO.fail(new RuntimeException("More than one authentication method found"))
                        }
    } yield auth
  }

  private[config] def loadKeyStream(source: KeySource): ZIO[Any, Throwable, InputStream] =
    ZIO.scoped {
      ZIO.fromAutoCloseable {
        source match {
          case KeySource.FromFile(path)     =>
            Task.attempt(new FileInputStream(path.toFile))
          case KeySource.FromBase64(base64) =>
            Task.attempt(new ByteArrayInputStream(Base64.getDecoder.decode(base64)))
          case KeySource.FromString(value)  =>
            Task.attempt(new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII)))
        }
      }
    }

  private def loadKeyString(source: KeySource): ZIO[Any, Throwable, String] =
    source match {
      case KeySource.FromFile(path)     =>
        ZIO.scoped(
          ZIO
            .fromAutoCloseable(Task.attempt(new FileInputStream(path.toFile)))
            .flatMap { stream =>
              Task.attempt(new String(stream.readAllBytes(), StandardCharsets.US_ASCII))
            }
        )
      case KeySource.FromBase64(base64) =>
        Task.attempt(new String(Base64.getDecoder.decode(base64), StandardCharsets.US_ASCII))
      case KeySource.FromString(value)  =>
        ZIO.succeed(value)
    }
}
