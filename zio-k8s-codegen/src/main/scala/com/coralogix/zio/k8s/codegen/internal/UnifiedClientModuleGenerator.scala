package com.coralogix.zio.k8s.codegen.internal

import com.coralogix.zio.k8s.codegen.internal.CodegenIO.{ format, writeTextFile }
import com.coralogix.zio.k8s.codegen.internal.Conversions.{ groupNameToPackageName, splitName }
import com.coralogix.zio.k8s.codegen.internal.UnifiedClientModuleGenerator._
import org.scalafmt.interfaces.Scalafmt

import scala.meta._
import zio.ZIO
import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.nio.file.Files

trait UnifiedClientModuleGenerator {
  this: ClientModuleGenerator =>

  def generateUnifiedClientModule(
    scalafmt: Scalafmt,
    targetRoot: Path,
    basePackageName: String,
    definitionMap: Map[String, IdentifiedSchema],
    resources: Set[SupportedResource]
  ): ZIO[Blocking, Throwable, Set[Path]] = {
    val gvkTree = toTree(resources)
    val source = generateUnifiedClientModuleSource(gvkTree, basePackageName, definitionMap)

    val pkg = basePackageName.split('.')
    val targetDir = pkg.foldLeft(targetRoot)(_ / _) / "kubernetes"
    for {
      _         <- Files.createDirectories(targetDir)
      targetPath = targetDir / "package.scala"
      _         <- writeTextFile(targetPath, source)
      _         <- format(scalafmt, targetPath)
    } yield Set(targetPath)
  }

  private def generateUnifiedClientModuleSource(
    gvkTree: PackageNode,
    basePackageName: String,
    definitionMap: Map[String, IdentifiedSchema]
  ): String = {
    val liveClass =
      pkgNodeToDef(definitionMap, basePackageName, "KubernetesApi", gvkTree, isTopLevel = true)
    val pkg = basePackageName
      .parse[Term]
      .get
      .asInstanceOf[Term.Ref]

    q"""package $pkg {

        import com.coralogix.zio.k8s.client.model.{K8sCluster, ResourceMetadata}
        import com.coralogix.zio.k8s.client.impl.{ResourceClient, ResourceStatusClient, SubresourceClient}
        import sttp.client3.httpclient.zio.SttpClient
        import zio.{ Has, Task, ZIO, ZLayer }

        package object kubernetes {

        $liveClass

        type Kubernetes = Has[KubernetesApi]

        val live: ZLayer[SttpClient with Has[K8sCluster], Nothing, Kubernetes] =
                  ZLayer.fromServices[SttpClient.Service, K8sCluster, KubernetesApi] {
                    (backend: SttpClient.Service, cluster: K8sCluster) => {
                      new KubernetesApi(backend, cluster)
                    }
                  }
        }
        }
     """.toString
  }

  private def pkgNodeToDef(
    definitionMap: Map[String, IdentifiedSchema],
    basePackageName: String,
    name: String,
    node: PackageNode,
    isTopLevel: Boolean = false
  ): Defn = {
    val childDefs = node.children.map { case (childName, node) =>
      node match {
        case pkgNode: PackageNode   =>
          pkgNodeToDef(definitionMap, basePackageName, childName, pkgNode)
        case ResourceNode(resource) =>
          resourceToDef(definitionMap, basePackageName, childName, resource)
      }
    }.toList

    if (isTopLevel) {
      topLevelToDef(name, childDefs)
    } else {
      innerLevelToDef(name, childDefs)
    }
  }

  private def topLevelToDef(name: String, childDefs: List[Stat]): Defn = {
    val nameT = Type.Name(name)
    q"""
       class $nameT(backend: SttpClient.Service, cluster: K8sCluster) {
         ..${childDefs}
       }
     """
  }

  private def innerLevelToDef(name: String, childDefs: List[Stat]): Defn = {
    val nameTerm = Term.Name(name)
    q"""
       object $nameTerm {
         ..${childDefs}
       }
     """
  }

  private def resourceToDef(
    definitionMap: Map[String, IdentifiedSchema],
    basePackageName: String,
    name: String,
    resource: SupportedResource
  ): Stat = {
    val pkg =
      if (resource.gvk.group.nonEmpty)
        s"$basePackageName.${groupNameToPackageName(resource.gvk.group).mkString(".")}.${resource.gvk.version}.${resource.plural}"
          .parse[Term]
          .get
          .asInstanceOf[Term.Ref]
      else
        s"$basePackageName.${resource.gvk.version}.${resource.plural}"
          .parse[Term]
          .get
          .asInstanceOf[Term.Ref]

    val (entityPkg, entity) = splitName(resource.modelName)
    val dtoPackage = ("com.coralogix.zio.k8s.model." + entityPkg.mkString("."))
      .parse[Term]
      .get
      .asInstanceOf[Term.Ref]

    val namePat = Pat.Var(Term.Name(name))

    val statusEntity = findStatusEntity(definitionMap, resource.modelName).map(s =>
      s"com.coralogix.zio.k8s.model.$s"
    )
    val entityT = Type.Select(dtoPackage, Type.Name(entity))
    val statusT = statusEntity.map(s => s.parse[Type].get).getOrElse(t"Nothing")
    val cons =
      getClientConstruction(
        "com.coralogix.zio.k8s.model",
        statusEntity,
        resource.subresources.map(_.id),
        entityT,
        statusT,
        fullyQualifiedSubresourceModels = true
      )

    val serviceT = Type.Select(pkg, Type.Name("Service"))
    val liveInit = Init(
      Type.Select(pkg, Type.Name("Live")),
      Name.Anonymous(),
      List(cons)
    )
    q"""lazy val $namePat: $serviceT = {
          val resourceType = implicitly[ResourceMetadata[$entityT]].resourceType
          new $liveInit
        }"""
  }

  private def toTree(resources: Set[SupportedResource]): PackageNode = {
    def insertAt(node: PackageNode, pkg: Vector[String], resource: SupportedResource): PackageNode =
      pkg match {
        case Vector(name)      =>
          node.children.get(name) match {
            case Some(_) => throw new IllegalStateException(s"Collision of name $name")
            case None    => node.copy(children = node.children.updated(name, ResourceNode(resource)))
          }
        case name +: remaining =>
          node.children.get(name) match {
            case Some(pn @ PackageNode(_)) =>
              node.copy(children = node.children.updated(name, insertAt(pn, remaining, resource)))
            case Some(ResourceNode(_))     =>
              throw new IllegalStateException(s"Collision of name $name")
            case None                      =>
              node.copy(children =
                node.children.updated(name, insertAt(PackageNode(Map.empty), remaining, resource))
              )
          }
      }

    def insert(root: PackageNode, resource: SupportedResource): PackageNode = {
      val groupName = groupNameToPackageName(resource.gvk.group)
      val pkg = groupName :+ resource.gvk.version :+ resource.plural
      insertAt(root, pkg, resource)
    }

    resources.foldLeft(PackageNode(Map.empty))(insert)
  }
}

object UnifiedClientModuleGenerator {
  sealed trait ResourceTreeNode
  final case class PackageNode(children: Map[String, ResourceTreeNode]) extends ResourceTreeNode
  final case class ResourceNode(resource: SupportedResource) extends ResourceTreeNode
}
