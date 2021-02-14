---
id: overview_gettingstarted
title: "Getting started"
---

## Dependencies

Start by adding `zio-k8s-client` as a dependency to your project:


```scala mdoc:passthrough

println(s"""```scala""")
if (zio.k8s.BuildInfo.isSnapshot)
  println(s"""resolvers += Resolver.sonatypeRepo("snapshots")""")
println(s"""libraryDependencies += "com.coralogix" %% "zio-k8s-client" % "${zio.k8s.BuildInfo.version}"""")
println(s"""```""")

```

in addition to this, you need to choose an [sttp](https://sttp.softwaremill.com/en/latest/) _backend_ that `zio-k8s` will use to make HTTP requests with. There are two official backends that can be used out of the box:

In case your application runs on **Java 11** or above, choose the `HttpClient` based version:

```scala
"com.softwaremill.sttp.client3" %% "httpclient-backend-zio" % "3.1.1"
"com.softwaremill.sttp.client3" %% "slf4j-backend"          % "3.1.1"
```

Otherwise choose the `async-http-client` based backend:

```scala
"com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % "3.1.1"
"com.softwaremill.sttp.client3" %% "slf4j-backend"                 % "3.1.1"
```

## Configuration

With all the dependencies added, we have to create an _sttp client_ and a _k8s cluster_ ZIO layers. 
This two together specifies how to connect to the _Kubernetes API_. There are [zio-config](https://zio.github.io/zio-config/) _descriptors_ for the data structures necessary to configure this.

### Providing configuration from code

```scala mdoc:silent
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import sttp.client3._
import sttp.model._
import zio.ZLayer
import zio.blocking.Blocking
import zio.nio.core.file.Path

// Configuration
val clientConfig = K8sClientConfig(
    insecure = false, // set true for testing locally with minikube
    debug = false,
    cert = Path("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
)

val clusterConfig = K8sClusterConfig(
    host = uri"https://kubernetes.default.svc",
    token = None,
    tokenFile = Path("/var/run/secrets/kubernetes.io/serviceaccount/token")
)
```

```scala mdoc:silent
// K8s configuration and client layers
val client = ZLayer.succeed(clientConfig) >>> k8sSttpClient
val cluster = Blocking.any ++ ZLayer.succeed(clusterConfig) >>> k8sCluster
```

### Configuring with zio-config + Typesafe Config

```scala mdoc:silent:reset
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import zio.blocking.Blocking
import zio.config.magnolia.DeriveConfigDescriptor._
import zio.config.magnolia.name
import zio.config.syntax._
import zio.config.typesafe._

case class Config(cluster: K8sClusterConfig, @name("k8s-client") client: K8sClientConfig)

// Loading config from HOCON
val configDesc = descriptor[Config]
val config = TypesafeConfig.fromDefaultLoader[Config](configDesc)

// K8s configuration and client layers
val client = config.narrow(_.client) >>> k8sSttpClient
val cluster = (Blocking.any ++ config.narrow(_.cluster)) >>> k8sCluster
```

and place the configuration in `application.conf`, for example:

```conf
k8s-client {
  insecure = false
  debug = false
  cert = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt" # not used if insecure
}

cluster {
  host = "https://kubernetes.default.svc" # Default
  token-file = "/var/run/secrets/kubernetes.io/serviceaccount/token"
}
```

## Clients

The above created `client` and `cluster` modules can be fed to any of the `zio-k8s` **client modules**
to access _Kubernetes_ resources. This is explained in details in the [resources](resources.md) section. 

The following example demonstrates how to gain access to the _Kubernetes pods and config maps_:

```scala mdoc:silent
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.pods.Pods

val k8s = (cluster ++ client) >>> (Pods.live ++ ConfigMaps.live)
```

## Notes
This library is built on the _ZIO modules and layers concept_. To learn more about that, 
check [the official documentation](https://zio.dev/docs/howto/howto_use_layers).