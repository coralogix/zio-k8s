package com.coralogix.zio.k8s.client.model

/** Resource version options for listing resource
  *
  * See https://kubernetes.io/docs/reference/using-api/api-concepts/#the-resourceversion-parameter
  */
sealed trait ListResourceVersion {
  def resourceVersion: Option[String]
  def resourceVersionMatch: Option[String]
}

object ListResourceVersion {

  /** Return data at the most recent resource version.
    *
    * The returned data must be consistent (i.e. served from etcd via a quorum read).
    */
  case object MostRecent extends ListResourceVersion {
    override def resourceVersion: Option[String] = None
    override def resourceVersionMatch: Option[String] = None
  }

  /** Return data at any resource version.
    *
    * The newest available resource version is preferred, but strong consistency is not required;
    * data at any resource version may be served.
    *
    * It is possible for the request to return data at a much older resource version that the client
    * has previously observed, particularly in high availability configurations, due to partitions
    * or stale caches. Clients that cannot tolerate this should not use this semantic.
    */
  case object Any extends ListResourceVersion {
    override def resourceVersion: Option[String] = Some("0")
    override def resourceVersionMatch: Option[String] = Some("NotOlderThan")
  }

  /** Return data at the exact resource version provided.
    *
    * If the provided resourceVersion is unavailable, the server responds with HTTP 410 "Gone". For
    * list requests to servers that honor the resourceVersionMatch parameter, this guarantees that
    * resourceVersion in the ListMeta is the same as the requested resourceVersion, but does not
    * make any guarantee about the resourceVersion in the ObjectMeta of the list items since
    * ObjectMeta.resourceVersion tracks when an object was last updated, not how up-to-date the
    * object is when served.
    *
    * @param version
    *   the exact resource version
    */
  final case class Exact(version: String) extends ListResourceVersion {
    override def resourceVersion: Option[String] = Some(version)
    override def resourceVersionMatch: Option[String] = Some("Exact")
  }

  /** Return data at least as new as the provided resourceVersion.
    *
    * The newest available data is preferred, but any data not older than the provided
    * resourceVersion may be served. For list requests to servers that honor the
    * resourceVersionMatch parameter, this guarantees that resourceVersion in the ListMeta is not
    * older than the requested resourceVersion, but does not make any guarantee about the
    * resourceVersion in the ObjectMeta of the list items since ObjectMeta.resourceVersion tracks
    * when an object was last updated, not how up-to-date the object is when served.
    *
    * @param version
    *   the provided resource version
    */
  final case class NotOlderThan(version: String) extends ListResourceVersion {
    override def resourceVersion: Option[String] = Some(version)
    override def resourceVersionMatch: Option[String] = Some("NotOlderThan")
  }
}
