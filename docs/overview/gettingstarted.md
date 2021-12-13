---
id: overview_gettingstarted
title: "Getting started"
---

## Dependencies

Start by adding `zio-k8s-client` as a dependency to your project:


```scala mdoc:passthrough

println(s"""```scala""")
if (com.coralogix.zio.k8s.BuildInfo.isSnapshot)
  println(s"""resolvers += Resolver.sonatypeRepo("snapshots")""")
println(s"""libraryDependencies += "com.coralogix" %% "zio-k8s-client" % "${com.coralogix.zio.k8s.BuildInfo.version}"""")
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
This two together specifies how to connect to the _Kubernetes API_. There are several layer definitions and
[zio-config](https://zio.github.io/zio-config/) _descriptors_ for the data structures necessary to configure this.

### Default automatic configuration
The simplest way to configure `zio-k8s` is to use the `k8sDefault` layer from the config package depending on 
the chosen HTTP implementation. In case of `HttpClient` this looks like the following:

```scala mdoc:silent
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import zio._
import zio.blocking.Blocking
import zio.system.System

import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.pods.Pods


k8sDefault >>> (Pods.live ++ ConfigMaps.live)
```

This uses the _default configuration chain_ that:

- Checks the `KUBECONFIG` environment variable for a kubeconfig path, otherwise uses `~/.kube/config`
- If this file exists, it loads it and uses the _current context_ (same that `kubectl` would)
- If it does not exist, if tries to load the default _service account token_. This is available when the application runs from a pod of the cluster.

Note that `k8sDefault` produces two modules in a single pass:
- a `K8sCluster` module describing the Kubernetes cluster to connect to
- an `SttpClient` module containing the actual HTTP client implementation

The more custom functions in the `config` package are only producing a `K8sClusterConfig` layer which can be used as
an input for both `K8sCluster` and `SttpClient`. Assuming we have a custom cluster configuration layer `config`:

```scala mdoc:silent
def customConfig: ZLayer[Any, Nothing, Has[K8sClusterConfig]] = ???

def customK8s = (Blocking.any ++ System.any ++ customConfig) >>> (k8sCluster ++ k8sSttpClient)
```

#### Trailing dots
In some cases the kubeconfig may contain cluster host names with a _trailing dot_. This is causing problems with the SSL engine
and currently can only used with the following workaround:

```scala mdoc:silent
val workaroundConfig = kubeconfig(disableHostnameVerification = true)
      .project(cfg => cfg.dropTrailingDot)
```

### Providing configuration from code

```scala mdoc:silent
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import sttp.client3._
import sttp.model._
import zio.ZLayer
import zio.blocking.Blocking
import zio.nio.file.Path

// Configuration
val config = ZLayer.succeed(
      K8sClusterConfig(
        host = uri"https://kubernetes.default.svc",
        authentication = K8sAuthentication.ServiceAccountToken(
          KeySource.FromFile(
            Path("/var/run/secrets/kubernetes.io/serviceaccount/token")
          )
        ),
        K8sClientConfig(
          debug = false,
          K8sServerCertificate.Secure(
            certificate = KeySource.FromFile(
              Path("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
            ),
            disableHostnameVerification = false
          )
        )
      )
    )
```

```scala mdoc:silent
// K8s configuration and client layers
val client = Blocking.any ++ System.any ++ config >>> k8sSttpClient
val cluster = Blocking.any ++ System.any ++ config >>> k8sCluster
```

### Configuring with zio-config + Typesafe Config

```scala mdoc:silent:reset
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import zio.blocking.Blocking
import zio.config.ConfigDescriptor
import zio.config.typesafe._
import zio.system.System

case class Config(k8s: K8sClusterConfig)

// Loading config from HOCON
val configDesc = ConfigDescriptor.nested("k8s")(clusterConfigDescriptor).to[Config]
val config = TypesafeConfig.fromDefaultLoader[Config](configDesc)

// K8s configuration and client layers
val client = (Blocking.any ++ System.any ++ config.project(_.k8s)) >>> k8sSttpClient
val cluster = (Blocking.any ++ System.any ++ config.project(_.k8s)) >>> k8sCluster
```

and place the configuration in `application.conf`, for example:

```conf
k8s {
  host = "https://kubernetes.default.svc"
  authentication {
    serviceAccountToken {
      path = "/var/run/secrets/kubernetes.io/serviceaccount/token"
    }
  }
  client {
    debug = false
    secure {
      certificate {
        path = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
      }
      disableHostnameVerification = false
    }
  }
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