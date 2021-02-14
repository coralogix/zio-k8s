---
id: internals_clients
title:  "Client implementation"
---

The generated code is just a facade on the generic Kubernetes client implementations that are hand-written in the `zio-k8s-client` subproject.

In this section we will go through the building blocks of the generic part and see their purpose.

- `trait Resource[T]` is the base interface for manipulating Kubernetes resources. It is independent from whether the resource is _namespaced_ or _cluster level_.
- `trait NamespacedResource[T]` and `trait ClusterResource[T]` define more type safe and convenient interfaces for the same thing, by having non-optional namespace parameters or not having namespace parameters at all, according to the semantics of the resource.
- `trait ResourceClientBase` holds some common functionality for all resource client implementations (resources, statuses, other subresources)
- `final class ResourceClient[T: K8sObject: Encoder: Decoder]` is the generic Kubernetes client impementation, implementing `Resource[T]`

Following the same logic we have `ResourceStatus[StatusT, T]` and its narrowed versions, `NamespacedResourceStatus` and `ClusterResourceStatus` with the implementation class `ResourceStatusClient`, and also for _subresources_ `Subresource` and `SubresourceClient`.

For _subresources_ the narrowed versions are not generic but being generated in by the code generator, because they also introduce a subresource-specific alias for the operations implemented by the base trait, such as `getScale` vs `getLog`. This is necessary to be able to create unified resource interfaces with all the supported subresources mixed together.

