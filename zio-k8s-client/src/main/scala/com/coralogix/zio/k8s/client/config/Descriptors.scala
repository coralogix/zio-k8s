package com.coralogix.zio.k8s.client.config

import sttp.model.Uri
import zio.config.ConfigDescriptor._
import zio.config._
import zio.nio.file.Path

/** Defines ZIO Config descriptors for all the configuration data types of zio-k8s
  */
trait Descriptors {
  private val uri: ConfigDescriptor[Uri] =
    string.transformOrFail(
      s => Uri.parse(s),
      (uri: Uri) => Right(uri.toString)
    )

  private val path: ConfigDescriptor[Path] =
    string.transform(
      s => Path(s),
      (path: Path) => path.toString()
    )

  private val keySourceFromFile: ConfigDescriptor[KeySource] =
    nested("path")(path).transformOrFailRight(
      (s: Path) => KeySource.FromFile(s),
      {
        case KeySource.FromFile(path) => Right(path)
        case _                        => Left("Not a KeySource.FromFile")
      }
    )

  private val keySourceFromBase64: ConfigDescriptor[KeySource] =
    nested("base64")(string).transformOrFailRight(
      (s: String) => KeySource.FromBase64(s),
      {
        case KeySource.FromBase64(base64) => Right(base64)
        case _                            => Left("Not a KeySource.FromBase64")
      }
    )

  private val keySourceFromString: ConfigDescriptor[KeySource] =
    nested("value")(string).transformOrFailRight(
      (s: String) => KeySource.FromString(s),
      {
        case KeySource.FromString(value) => Right(value)
        case _                           => Left("Not a KeySource.FromString")
      }
    )

  private val keySource: ConfigDescriptor[KeySource] =
    keySourceFromFile <> keySourceFromString <> keySourceFromBase64

  private val tokenCacheSeconds: ConfigDescriptor[Int] =
    int("tokenCacheSeconds")
      .optional
      .transform(
        _.getOrElse(0),
        value => if (value == 0) None else Some(value)
      )

  private val serviceAccountToken: ConfigDescriptor[K8sAuthentication] =
    nested("serviceAccountToken")((keySource |@| tokenCacheSeconds).tupled).transformOrFailRight(
      { case (token, cacheSeconds) =>
        K8sAuthentication.ServiceAccountToken(token, cacheSeconds)
      },
      {
        case K8sAuthentication.ServiceAccountToken(token, cacheSeconds) =>
          Right((token, cacheSeconds))
        case _                                                          =>
          Left("Not a K8sAuthentication.ServiceAccountToken")
      }
    )

  private val basicAuth: ConfigDescriptor[K8sAuthentication] =
    nested("basicAuth")(
      (string("username") <*> string("password"))
    ).transformOrFailRight(
      { case (username, password) => K8sAuthentication.BasicAuth(username, password) },
      {
        case K8sAuthentication.BasicAuth(username, password) => Right((username, password))
        case _                                               => Left("Not a K8sAuthentication.BasicAuth")
      }
    )

  private val clientCertificates: ConfigDescriptor[K8sAuthentication] =
    nested("clientCertificates")(
      (nested("certificate")(keySource) |@| nested("key")(keySource) |@| string(
        "password"
      ).optional).tupled
    ).transformOrFailRight(
      { case (certificate, key, password) =>
        K8sAuthentication.ClientCertificates(certificate, key, password)
      },
      {
        case K8sAuthentication.ClientCertificates(certificate, key, password) =>
          Right((certificate, key, password))
        case _                                                                => Left("Not a K8sAuthentication.ClientCertificates")
      }
    )

  private val k8sAuthentication: ConfigDescriptor[K8sAuthentication] =
    serviceAccountToken <> basicAuth <> clientCertificates

  private val insecureServerCertificate: ConfigDescriptor[K8sServerCertificate] =
    boolean("insecure").transformOrFail(
      {
        case true  => Right(K8sServerCertificate.Insecure)
        case false => Left("Use insecure: true or secure")
      },
      {
        case K8sServerCertificate.Insecure => Right(true)
        case _                             => Left("Not a K8sServerCertificate.Insecure")
      }
    )

  private val secureServerCertificate: ConfigDescriptor[K8sServerCertificate] =
    nested("secure")(
      nested("certificate")(keySource) <*> boolean("disableHostnameVerification")
    ).transformOrFailRight(
      { case (cert, disableHostnameVerification) =>
        K8sServerCertificate.Secure(cert, disableHostnameVerification)
      },
      {
        case K8sServerCertificate.Secure(cert, disableHostnameVerification) =>
          Right((cert, disableHostnameVerification))
        case K8sServerCertificate.Insecure                                  => Left("Not a K8sServerCertificate.Secure")
      }
    )

  private val serverCertificate: ConfigDescriptor[K8sServerCertificate] =
    insecureServerCertificate <> secureServerCertificate

  private val clientConfig: ConfigDescriptor[K8sClientConfig] =
    (boolean("debug") |@| serverCertificate).to[K8sClientConfig]

  /** ZIO Config descriptor for [[K8sClusterConfig]]
    */
  val clusterConfigDescriptor: ConfigDescriptor[K8sClusterConfig] =
    (nested("host")(uri) |@| nested("authentication")(k8sAuthentication) |@| nested("client")(
      clientConfig
    )).to[K8sClusterConfig]
}
