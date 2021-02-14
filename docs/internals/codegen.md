---
id: internals_codegen
title:  "Code generation pipeline"
---

The `zio-k8s-client` library consists of hand-written base code and a huge number of generated scala files. This code generation step is implemented as an `sbt` _code generator_. 

It is responsible for generating the following _types_ of code:
- Kubernetes model classes
- Monocle optics for the model classes
- Kubernetes resource client modules
- Subresource interfaces
- The unified kubernetes client module

Some parts of the `zio-k8s-client` plugin is shared with the public `zio-k8s-crd` plugin. The source code is generated using [Scalameta](https://scalameta.org/) and formatted with [Scalafmt](https://scalameta.org/scalafmt/). The source OpenAPI specification is parsed with the official Java swagger parser library.

The following list describes the primary steps of the code generator:

- Downloading and parsing the _Kubernetes OpenAPI specification_ from the official [Kubernetes GitHub repo](https://github.com/kubernetes/kubernetes/)
- **Identifying** the model definitions
- **Classifying** the endpoints
- Generating the **subresource interfaces**
- Generating **client modules**
- Generating **models**
- Generating the **unified client module**

The monocle optics code generator is invoked separately as a code generator for the `zio-k8s-client-monocle` subproject.

In the following sections we discuss the code generator steps.

## Identification
In the _identification_ phase, implemented by `IdentifiedSchema.identifyDefinition` and `IdentifiedPath.identifyPath`, the code enumerates _all_ model definitions and endpoints from the OpenAPI schema, extracts some information about them and stores them in subtypes of the `Identified` trait.

For model definitions this can be:
- `Regular` is a model schema with no special info about it. These are simple data types, not Kubernetes resources
- `IdentifiedDefinition` is a model that has an associated _Group/Version/Kind_ coordinate.

Path identification is more complicated. Results can be:
- `RegularAction` is an unidentified/not supported operation
- `ApiGroupInfo`, `ApiVersionInfo`, `ApiResourceListing`, `ApiGroupListing` and `GetKubernetesVersion` are special known endpoints 
- `IdentifiedAction` is a single endpoint that has an associated _Group/Version/Kind_ coordinate, and a specified _action verb_.

For each identified action we also run an _endpoint type detection_ (`EndpointType.detectEndpointType`) which examines the url, method, parameters and other properties of the endpoint and tags the action with one of the known endpoint types if possible.

This is a very important step, because this guarantees that the code generator **only generates client modules for resources that has exactly the expected endpoints**. Those endpoint that does not 100% match the expected properties cannot be safely called with the generic Kubernetes client, therefore they get rejected by the endpoint detector and later in the classification phase.

## Classification
The _classification_ phase collects all the identified actions and identifies supported resources and their actions from it. Its entry point is `ClassifiedResource.classifyActions`.

First we group the identified actions by GVK, then for each identified coordinate, we run the classification, passing all the identified actions to it. 

If the actions with their _detected endpoint types_ match the criteria of a supported kubernetes resource, the classification returns a `SupportedResource` otherwise an `UnsupportedResource`. 

Part of this step is to collect and verify the _subresources_ too.

### Validation, whitelist
In multiple points during the identification and classification the **unidentified/unsupported elements** are being checked against a **whitelist** implemented in the `Whitelist` object.

Everything that is not in the Whitelist breaks the build. This way we can detect breaking changes in case of a Kubernetes API upgrade.

The _whitelist_ can only allow items that are well understood and have a corresponding GitHub issue documenting it.

## Client code generator
The `ClientModuleGenerator` module is responsible for generating the packages that implement the _per-resource ZIO client modules_.

## Model generator
The `ModelGenerator` module is responsible for generating the _case classes_ for all the data types in the OpenAPI specification, with implementation of JSON codecs and the type classes described in [the overview](../overview/generic.md).

## Unified client module generator
The `UnifiedClientModuleGenerator` module outputs a single ZIO module that defines the unified _Kubernetes API_ service traits and its implementation. It is just an extra layer on top of the _per-resource modules_ so its implementation is relatively simple.
