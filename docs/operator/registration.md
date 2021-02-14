---
id : operator_registration
title:  "Auto-registering CRDs"
---

A small helper function provides the capability to register the CRDs supported by the operator at startup.
This takes advantage of a feature of `zio-k8s-crd` that generates a `customResourceDefinition` effect in the custom client module that retrieves the source YAML of the CRD that was used to generate the module. 

In the following example we have a custom resouce called `AlertSet` generated with `zio-k8s-crd` as [desribed on the CRD page](../crd/index.md), and the `registerIfMissing` ZIO effect will register that CRD into the _Kubernetes cluster_:

```scala
import com.coralogix.zio.k8s.operator.Registration
import com.coralogix.zio.k8s.client.com.coralogix.v1.alertsets
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet

Registration.registerIfMissing(alertsets.customResourceDefinition)
```

The registration requires the `Logging with Blocking with CustomResourceDefinitions` environment.
