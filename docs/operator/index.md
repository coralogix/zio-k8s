---
id: operator_index
title:  "Operator library"
---

The `zio-k8s-operator` library is a higher level library built on top of the client providing support for implementing [Kubernetes Operators](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/).

- [Implementing operators](operator.md) - shows how to launch operators for any resource and customize them with aspects
- [Auto-registering CRDs](registration.md) - register _custom resource definitions_ from the application at startup
- [Leader election](leaderelection.md) - a simple leader election mechanism to guarantee the operator runs only in a single instance

