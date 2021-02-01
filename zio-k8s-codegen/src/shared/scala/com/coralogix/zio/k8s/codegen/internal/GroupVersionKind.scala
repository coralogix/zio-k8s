package com.coralogix.zio.k8s.codegen.internal

case class GroupVersionKind(group: String, version: String, kind: String) {
  override def toString: String = s"$group/$version/$kind"
}
