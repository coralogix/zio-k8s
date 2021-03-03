---
id: operator_leaderelection
title:  "Leader election"
---

As an operator watches events coming from the whole _Kubernetes cluster_, running multiple instances of it can eaasily lead to undesired behavior. The `zio-k8s-operator` library implements a simple **leader election** mechanism (a port of the "leader for life" feature of the [Go operator framework](https://sdk.operatorframework.io/docs/building-operators/golang/advanced-topics/#leader-for-life)).

This works by creating a `ConfigMap` resource and using the _Kubernetes resource ownership_ to tie its lifecycle to the running `Pod`s lifecycle. This way (even though the code itself releases the `ConfigMap` on exit) if the pod crashes, the cluster will automatically garbage collect the config map resource and let another instance acquire the lock.

## Prerequisites
To be able to use this logic, some prerequisites have to be met:

- An environment variable with the active pod's name called `POD_NAME`
- Proper access rights for `Pod` and `ConfigMap` resources

To define the `POD_NAME` use the following in the `Pod` resource:

```yaml
env:
  - name: POD_NAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name
```

And for providing the required rights with RBAC in a `ClusterRole`:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: example-role
rules:
  - apiGroups:
      - ""
    resources:
      - namespaces
      - pods
    verbs: ["get", "list", "watch"]
  - apiGroups:
      - ""
    resources:
      - configmaps
    verbs: [ "get", "list", "create", "update" ]
```

## Guarding the code

Let's see how to guard our ZIO effect from running in multiple instances within the cluster!

```scala mdoc:invisible
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import sttp.client3._
import sttp.model._
import zio._
import zio.blocking.Blocking
import zio.nio.core.file.Path
```

```scala mdoc:silent
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.operator.Leader

import zio.blocking.Blocking
import zio.clock.Clock
import zio.logging._
import zio.system.System

val logging = Logging.console(
      logLevel = LogLevel.Debug,
      format = LogFormat.ColoredLogFormat()
    ) >>> Logging.withRootLoggerName("leader-example")

val k8s: ZLayer[Blocking with System, Throwable, Pods with ConfigMaps] = 
  k8sDefault >>> (Pods.live ++ ConfigMaps.live)

def example(): ZIO[
    Logging with Blocking with System with Clock with Pods with ConfigMaps,
    Nothing,
    Option[Nothing]
  ] =
    Leader.leaderForLife("leader-example-lock", None) {
      exampleLeader()
    }

def exampleLeader(): ZIO[Logging, Nothing, Nothing] =
    log.info(s"Got leader role") *> ZIO.never

example.provideCustomLayer(
    k8s ++ logging
)
```

Once we have the `Pods with ConfigMaps` requirements, as well as a ZIO `Logging` layer, protecting the application from running in multiple instances can happen by simply wrapping it in `Leader.leaderForLife`.

In case the lock is already taken, the code will block and retry periodically to gain access to the config map.

There is also a variant in the `Leader` module that returns a `ZManaged` for more precise control.