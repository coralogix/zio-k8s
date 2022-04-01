package com.coralogix.zio.k8s.client

import com.coralogix.zio.k8s.client.model.{ AttachedProcessState, K8sNamespace }
import zio.IO
import zio.stream.{ ZStream, ZTransducer }

/** Generic interface for subresources.
  *
  * Every subresource supports a different subset of these operations, so usually you should use the
  * actual generated subresource interfaces instead.
  *
  * @tparam T
  *   Subresource type
  */
trait Subresource[T] {

  /** Get the subresource by the resource's name
    * @param name
    *   Name of the resource
    * @param namespace
    *   Namespace. For namespaced resources it must be Some, for cluster resources it must be None.
    * @param customParameters
    *   A set of custom query parameters to pass to the Kubernetes API
    * @return
    *   The queried subresource
    */
  def get(
    name: String,
    namespace: Option[K8sNamespace],
    customParameters: Map[String, String] = Map.empty
  ): IO[K8sFailure, T]

  /** Get the subresource in a streaming way
    * @param name
    *   Name of the resource
    * @param namespace
    *   Namespace. For namespaced resources it must be Some, for cluster resources it must be None.
    * @param transducer
    *   Transducer to transform the response byte stream to the subresource type
    * @param customParameters
    *   A set of custom query parameters to pass to the Kubernetes API
    * @return
    *   A stream of the subresource type
    */
  def streamingGet(
    name: String,
    namespace: Option[K8sNamespace],
    transducer: ZTransducer[Any, K8sFailure, Byte, T],
    customParameters: Map[String, String] = Map.empty
  ): ZStream[Any, K8sFailure, T]

  /** Replaces the subresource given by its resource name
    * @param name
    *   Name of the resource
    * @param updatedValue
    *   Updated subresource value
    * @param namespace
    *   Namespace. For namespaced resources it must be Some, for cluster resources it must be None.
    * @param dryRun
    *   If true, the request is sent to the server but it will not create the resource.
    * @return
    *   The updated subresource value returned from the Kubernetes server
    */
  def replace(
    name: String,
    updatedValue: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T]

  /** Creates a new subresource for a resource given by its name
    * @param name
    *   Name of thte resource
    * @param value
    *   Subresource to create
    * @param namespace
    *   Namespace. For namespaced resources it must be Some, for cluster resources it must be None.
    * @param dryRun
    *   If true, the request is sent to the server but it will not create the resource.
    * @return
    *   The created subresource returned from the Kubernetes server
    */
  def create(
    name: String,
    value: T,
    namespace: Option[K8sNamespace],
    dryRun: Boolean
  ): IO[K8sFailure, T]

  def connect(
    name: String,
    namespace: Option[K8sNamespace],
    customParameters: Map[String, String] = Map.empty
  ): IO[K8sFailure, AttachedProcessState]

}
