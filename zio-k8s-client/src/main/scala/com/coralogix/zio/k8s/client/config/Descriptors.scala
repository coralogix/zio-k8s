package com.coralogix.zio.k8s.client.config

import sttp.model.Uri
import zio.Config.boolean
import zio.config.magnolia._
import zio.nio.file.Path
import zio.{ Chunk, Config }

/** Defines ZIO Config descriptors for all the configuration data types of zio-k8s
  */
trait Descriptors {
  private val uri: Config[Uri] =
    Config.string.mapOrFail { s =>
      Uri.parse(s).left.map(err => Config.Error.InvalidData(Chunk.empty, err))
    }

  private val path: Config[Path] = Config.string.map(Path(_))

  private val keySourceFromFile: Config[KeySource] =
    path.nested("path").map(p => KeySource.FromFile(p))

  private val keySourceFromBase64: Config[KeySource] =
    Config.string.nested("base64").map(s => KeySource.FromBase64(s))

  private val keySourceFromString: Config[KeySource] =
    Config.string.nested("value").map(s => KeySource.FromString(s))

  private val keySource: Config[KeySource] =
    keySourceFromFile orElse keySourceFromString orElse keySourceFromBase64

  private val serviceAccountToken: Config[K8sAuthentication] =
    keySource.nested("serviceAccountToken").map(ks => K8sAuthentication.ServiceAccountToken(ks))

  private val basicAuth: Config[K8sAuthentication] =
    (Config.string("username") zip Config.string("password")).nested("basicAuth") map {
      case (username, password) => K8sAuthentication.BasicAuth(username, password)
    }

  private val clientCertificates: Config[K8sAuthentication] =
    (keySource.nested("certificate") zip
      keySource.nested("key") zip
      Config.string("password").optional)
      .nested("clientCertificates")
      .map { case (cert, key, password) =>
        K8sAuthentication.ClientCertificates(cert, key, password)
      }

  private val k8sAuthentication: Config[K8sAuthentication] =
    serviceAccountToken orElse basicAuth orElse clientCertificates

  private val insecureServerCertificate: Config[K8sServerCertificate] =
    boolean("insecure").mapOrFail {
      case true  => Right(K8sServerCertificate.Insecure)
      case false => Left(Config.Error.InvalidData(Chunk.empty, "Use insecure: true or secure"))
    }

  private val secureServerCertificate: Config[K8sServerCertificate] =
    (keySource.nested("certificate") zip Config.boolean("disableHostnameVerification"))
      .nested("secure")
      .map { case (cert, disableHostnameVerification) =>
        K8sServerCertificate.Secure(cert, disableHostnameVerification)
      }

  private val serverCertificate: Config[K8sServerCertificate] =
    insecureServerCertificate orElse secureServerCertificate

  private val clientConfig: Config[K8sClientConfig] =
    (boolean("debug") zip serverCertificate).map { case (debug, cert) =>
      K8sClientConfig(debug, cert)
    }

  /** ZIO Config descriptor for [[K8sClusterConfig]]
    */
  val clusterConfigDescriptor: Config[K8sClusterConfig] =
    (uri.nested("host") zip
      k8sAuthentication.nested("authentication") zip
      clientConfig.nested("client"))
      .map { case (uri, auth, client) =>
        K8sClusterConfig(uri, auth, client)
      }

}
