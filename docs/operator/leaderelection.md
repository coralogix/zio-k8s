---
id: operator_leaderelection
title:  "Leader election"
---

As an operator watches events coming from the whole _Kubernetes cluster_, running multiple instances of it can eaasily lead to undesired behavior. The `zio-k8s-operator` library implements some simple **leader election** algorithms:

- a port of the "leader for life" feature of the [Go operator framework](https://sdk.operatorframework.io/docs/building-operators/golang/advanced-topics/#leader-for-life))
- a port of the `Lease` based lock mechanism in the [Go Kubernetes client](https://pkg.go.dev/k8s.io/client-go/tools/leaderelection)

## Leader for life algorithm
This works by creating a `ConfigMap` resource and using the _Kubernetes resource ownership_ to tie its lifecycle to the running `Pod`s lifecycle. This way (even though the code itself releases the `ConfigMap` on exit) if the pod crashes, the cluster will automatically garbage collect the config map resource and let another instance acquire the lock.

There is an alternative implementation that instead of working with `ConfigMap` resources uses its own custom resource called `LeaderLock`. This can be used in scenarios when giving permissions for arbitrary `ConfigMap` resources is undesirable. 

### Prerequisites
To be able to use this feature, some prerequisites have to be met:

- An environment variable with the active pod's name called `POD_NAME`
- Proper access rights for `Pod` and `ConfigMap` or `LeaderLock` resources
- When using `LeaderLock`, it's _custom resource definition_ must be installed

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

## Lease lock algorithm
This algorithm uses a `Lease` resource. If it does not exist it tries to create one. In case it exists it only needs `update`
rights for it. Other than that, similar to the other implementation it needs the `POD_NAME` environment variable
that is used as the lease holder's identity.

Note that unlike the _leaader for life_ methods, it is not guaranteed that the leadership is kept until the end of the process.

When using this method the code that is protected by the leader election might be interrupted when another instance 
steals the ownership. 

## Guarding the code

Let's see how to guard our ZIO effect from running in multiple instances within the cluster!

```scala mdoc:invisible
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import sttp.client3._
import sttp.model._
import zio._
import zio.nio.file.Path
```

```scala mdoc:silent
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.operator.contextinfo.ContextInfo
import com.coralogix.zio.k8s.operator.leader
import com.coralogix.zio.k8s.operator.leader.LeaderElection

import zio.Clock
import zio.System


def example(): ZIO[
    System with Clock with LeaderElection,
    Nothing,
    Option[Nothing]
  ] =
    leader.runAsLeader {
      exampleLeader()
    }

def exampleLeader(): ZIO[Any, Nothing, Nothing] =
    ZIO.logInfo(s"Got leader role") *> ZIO.never

example.provideCustom(
    k8sDefault,
    ContextInfo.live,
    Pods.live,
    ConfigMaps.live,
    LeaderElection.configMapLock("leader-example-lock")
)
```

We have to construct a `LeaderElection` layer with one of the layer constructors from the `LeaderElection` object. In this
example we are using the `ConfigMap` based lock. This lock has two requirements:

- `ContextInfo` to get information about the running pod
- `ConfigMaps` to access the `ConfigMaps`

The leader election module also depends on `Logging` to display information about the leader election process. 

Once we have the `LeaderElection` layer, protecting the application from running in multiple instances can happen by simply wrapping it in `leader.runAsLeader`.

In case the lock is already taken, the code will block and retry periodically to gain access to the config map.

When using the `Lease` based algorithm, it is possible that the ownership is taken away from the leader. In this case
the `runAsLeader` function returns with `None` and usually it is recommended to retry acquiring it, for example using
`.repeatWhile(_.isEmpty)`.

There is also a variant in the `LeaderElection` module that returns a `ZManaged` for more precise control.