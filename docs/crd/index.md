---
id: crd_index
title:  "Custom Resource Definition support"
---

[Custom resources](https://kubernetes.io/docs/concepts/extend-kubernetes/api-extension/custom-resources/) are a way to extend the _Kubernetes API_ with the capability to store custom data types just like the built-in _resources_.

Custom data types are supported by `zio-k8s` too with **code generation** from _Kubernetes_ `CustomResourceDefinition` resources by an `sbt` plugin called `zio-k8s-crd`.

The [How to work with custom resources](howto.md) page explains in detail how to use this feature. Everything written on the [Working with resources page](../overview/resources.md) is applicable for custom resources too.
