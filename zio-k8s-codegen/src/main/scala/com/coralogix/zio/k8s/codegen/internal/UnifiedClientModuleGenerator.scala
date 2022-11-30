package com.coralogix.zio.k8s.codegen.internal

import io.github.vigoo.metagen.core._
import com.coralogix.zio.k8s.codegen.internal.CodegenIO.writeTextFile
import com.coralogix.zio.k8s.codegen.internal.Conversions.{ groupNameToPackageName, splitName }
import com.coralogix.zio.k8s.codegen.internal.UnifiedClientModuleGenerator._
import org.scalafmt.interfaces.Scalafmt

import scala.meta._
import zio.{ Has, ZIO }
import zio.blocking.Blocking
import zio.nio.file.Path
import zio.nio.file.Files

import scala.collection.immutable

trait UnifiedClientModuleGenerator {
  this: Common with ClientModuleGenerator =>

  def generateUnifiedClientModule(
    basePackageName: String,
    definitionMap: Map[String, IdentifiedSchema],
    resources: Set[SupportedResource]
  ): ZIO[Has[Generator] with Blocking, GeneratorFailure[Nothing], Set[Path]] = {
    val gvkTree = toTree(resources)
    val parts = basePackageName.split('.')
    val pkg = Package(parts.head, parts.tail: _*)
    for {
      targetPath <- Generator.generateScalaPackageObject[Any, Nothing](pkg, "kubernetes") {
                      generateUnifiedClientModuleCode(gvkTree, basePackageName, definitionMap)
                    }
    } yield Set(targetPath)
  }

  private def generateUnifiedClientModuleCode(
    gvkTree: PackageNode,
    basePackageName: String,
    definitionMap: Map[String, IdentifiedSchema]
  ): ZIO[Has[CodeFileGenerator], Nothing, Term.Block] = {
    val interfaces =
      pkgNodeToInterfaces(definitionMap, basePackageName, "Service", gvkTree)
    val liveClass =
      pkgNodeToDef(definitionMap, basePackageName, "Api", gvkTree, Vector.empty, isTest = false)
    val testClass =
      pkgNodeToDef(definitionMap, basePackageName, "TestApi", gvkTree, Vector.empty, isTest = true)
    val pkg = basePackageName
      .parse[Term]
      .get
      .asInstanceOf[Term.Ref]

    val liveLayer =
      q"""val live: ZLayer[Has[SttpBackend[Task, ZioStreams with WebSockets]] with Has[K8sCluster], Nothing, Kubernetes] =
            ZLayer.fromServices[SttpBackend[Task, ZioStreams with WebSockets], K8sCluster, Service] {
              (backend: SttpBackend[Task, ZioStreams with WebSockets], cluster: K8sCluster) => {
                new Api(backend, cluster)
            }
          }
       """

    val anyLayer =
      q"""val any: ZLayer[Kubernetes, Nothing, Kubernetes] = ZLayer.requires[Kubernetes]"""

    val testLayer =
      q"""val test: ZLayer[Any, Nothing, Kubernetes] =
            ZIO.runtime[Any].map { runtime => new TestApi(runtime) }.toLayer
       """

    val defs = interfaces ++ List(liveClass, testClass, liveLayer, anyLayer, testLayer)

    ZIO.succeed {
      q"""
          type Kubernetes = Has[Kubernetes.Service]
          object Kubernetes {
            ..$defs
          }
     """
    }
  }

  private def toInterfaceName(name: String): String =
    name.capitalize + "Service"

  private def pkgNodeToInterfaces(
    definitionMap: Map[String, IdentifiedSchema],
    basePackageName: String,
    name: String,
    node: PackageNode
  ): List[Defn] = {
    val childInterfaces = node.children
      .collect { case (childName, pkgNode: PackageNode) =>
        pkgNodeToInterfaces(
          definitionMap,
          basePackageName,
          toInterfaceName(childName),
          pkgNode
        )
      }
      .flatten
      .toList

    val children = node.children.collect { case (childName, pkgNode: PackageNode) =>
      q"""def ${Term.Name(childName)}: ${Type.Select(
        Term.Name(name),
        Type.Name(toInterfaceName(childName))
      )}"""
    }.toList

    val resources = node.children.collect { case (childName, ResourceNode(resource)) =>
      resourceToInterfaceDef(basePackageName, childName, resource)
    }.toList

    List(
      Some(q"""
       trait ${Type.Name(name)} {
          ..$children
          ..$resources
       }
       """),
      if (childInterfaces.nonEmpty)
        Some(
          q"""
       object ${Term.Name(name)} {
         ..$childInterfaces
       }
       """
        )
      else None
    ).flatten
  }

  private def resourceToInterfaceDef(
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

    val nameTerm = Term.Name(name)

    val obj = Term.Select(pkg, Term.Name(resource.pluralEntityName))
    val serviceT = Type.Select(obj, Type.Name("Service"))

    q"""def $nameTerm: $serviceT"""
  }

  private def pkgNodeToDef(
    definitionMap: Map[String, IdentifiedSchema],
    basePackageName: String,
    name: String,
    node: PackageNode,
    ifaceStack: Vector[String],
    isTest: Boolean
  ): Defn = {
    val childDefs = node.children.map { case (childName, node) =>
      node match {
        case pkgNode: PackageNode   =>
          pkgNodeToDef(
            definitionMap,
            basePackageName,
            childName,
            pkgNode,
            if (ifaceStack.isEmpty)
              Vector("Service")
            else
              ifaceStack :+ toInterfaceName(name),
            isTest
          )
        case ResourceNode(resource) =>
          resourceToDef(definitionMap, basePackageName, childName, resource, isTest)
      }
    }.toList

    if (ifaceStack.isEmpty) {
      topLevelToDef(name, childDefs, isTest)
    } else {
      innerLevelToDef(name, childDefs, ifaceStack :+ toInterfaceName(name))
    }
  }

  private def topLevelToDef(name: String, childDefs: List[Stat], isTest: Boolean): Defn = {
    val nameT = Type.Name(name)
    if (isTest)
      q"""
       class $nameT(runtime: Runtime[Any]) extends Service {
         ..$childDefs
       }
     """
    else
      q"""
       class $nameT(backend: SttpBackend[Task, ZioStreams with WebSockets], cluster: K8sCluster) extends Service {
         ..$childDefs
       }
     """
  }

  private def innerLevelToDef(
    name: String,
    childDefs: List[Stat],
    ifaceStack: Vector[String]
  ): Defn = {
    val namePat = Pat.Var(Term.Name(name))
    val ifaceType = Init(ifaceStack.mkString(".").parse[Type].get, Name.Anonymous(), List.empty)
    q"""
       lazy val $namePat = new $ifaceType {
         ..$childDefs
       }
     """
  }

  private def resourceToDef(
    definitionMap: Map[String, IdentifiedSchema],
    basePackageName: String,
    name: String,
    resource: SupportedResource,
    isTest: Boolean
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

    val entity = splitName(resource.schemaName)

    val nameTerm = Term.Name(name)
    val namePat = Pat.Var(nameTerm)

    val statusEntity = findStatusEntity(Packages.k8sModel, definitionMap, resource.schemaName)

    val status = statusEntity.getOrElse(ScalaType.nothing)

    val deleteResponse = resource.actions
      .map(_.endpointType)
      .collectFirst { case EndpointType.Delete(_, _, responseTypeRef) => responseTypeRef }
      .getOrElse(Types.status)

    val isStandardDelete = deleteResponse == Types.status

    val obj = Term.Select(pkg, Term.Name(resource.pluralEntityName))
    val serviceT = Type.Select(obj, Type.Name("Service"))

    if (isTest) {
      val testClientConstruction: List[(Term, Enumerator)] = getTestClientConstruction(
        statusEntity.isDefined,
        resource.subresources.map(_.id),
        entity,
        status,
        deleteResponse
      )

      val liveInit = Init(
        Type.Select(obj, Type.Name("Live")),
        Name.Anonymous(),
        List(testClientConstruction.map(_._1))
      )

      q"""lazy val $namePat: $serviceT = {
            runtime.unsafeRun {
              for {
                ..${testClientConstruction.map(_._2)}
              } yield new $liveInit
            }
          }"""
    } else {
      val cons =
        getClientConstruction(
          statusEntity.isDefined,
          resource.subresources.map(_.id),
          entity,
          status,
          deleteResponse
        )

      val liveInit = Init(
        Type.Select(obj, Type.Name("Live")),
        Name.Anonymous(),
        List(cons)
      )
      q"""lazy val $namePat: $serviceT = {
            val resourceType = implicitly[${Types.resourceMetadata(entity).typ}].resourceType
            new $liveInit
          }"""
    }
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
