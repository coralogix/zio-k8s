---
id: overview_testing
title: "Test clients"
---

Each _Kubernetes client module_ - both the _per-resource_ modules and the _unified_ module also provide a **test layer** beside the live one. These test layers have no requirements and are intended to be used in unit tests to mock the real Kubernetes API.

The following example creates a config map and checks if it has been created:

```scala mdoc:silent
import com.coralogix.zio.k8s.client.v1.configmaps
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.K8sFailure.syntax._

import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import com.coralogix.zio.k8s.model.core.v1.ConfigMap

val test = for {
    _ <- configmaps.create(
        ConfigMap(
            metadata = ObjectMeta(name = "test"),
            data = Map("x" -> "y"),
        ),
        K8sNamespace.default)
    containsTest <- 
      configmaps
        .get("test", K8sNamespace.default)
        .ifFound
        .map(_.nonEmpty)
} yield containsTest

test.provideLayer(ConfigMaps.test)
```
