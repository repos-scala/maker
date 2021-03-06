package maker.utils

import org.apache.ivy.ant.IvyMakePom
import org.apache.ivy.plugins.parser.m2.PomWriterOptions
import xml.Elem
import java.io.File

trait Scope
case object Compile extends Scope
case object Test extends Scope
case object Provided extends Scope

case class ScalaVersion(version : Version)
object ScalaVersion {
  def apply(version : String) : ScalaVersion = ScalaVersion(Version(version))
}

// mvn group -> artifact -> version <-> ivy org -> name -> rev
object ModuleId {
  implicit def toGroupId(id : String) : GroupId = new GroupId(id)
  implicit def toGroupArtifactAndVersion(groupAndArtifact : GroupAndArtifact) : GroupArtifactAndVersion =
    GroupArtifactAndVersion(groupAndArtifact.groupId, groupAndArtifact.artifactId, None)
}
case class GroupId(id : String) {
  def %(artifactId : String) = GroupAndArtifact(this, ArtifactId(artifactId))
  def %%(artifactId : String)(implicit scalaVersion : ScalaVersion) = GroupAndArtifact(this, ArtifactId(artifactId + "_" + scalaVersion.version.version))
}
case class ArtifactId(id : String)
trait GAV {
  val groupId : GroupId
  val artifactId : ArtifactId
  val version : Option[Version] = None
  def toGroupAndArtifact = GroupAndArtifact(groupId, artifactId)
  def toGroupArtifactAndVersion = GroupArtifactAndVersion(groupId, artifactId, None)
  def withVersion(version : Version) = GroupArtifactAndVersion(groupId, artifactId, Some(version))

  def toIvyInclude : Elem = <dependency org={groupId.id} name={artifactId.id} rev={version.map(v => xml.Text(v.version))} />
  def toIvyExclude : Elem = <exclude org={groupId.id} module={artifactId.id} />
}
case class GroupAndArtifact(groupId : GroupId, artifactId : ArtifactId) extends GAV {
  def %(version : String) = GroupArtifactAndVersion(groupId, artifactId, Some(Version(version)))
  override def toString = groupId.id + ":" + artifactId.id
}
case class Version(version : String)
case class GroupArtifactAndVersion(groupId : GroupId, artifactId : ArtifactId, override val version : Option[Version]) extends GAV {
  override def toString = groupId.id + ":" + artifactId.id + ":" + version.map(_.version).getOrElse("")
  def toPath = groupId.id + File.separator + artifactId.id
}

/**
 * Simple case class representing a library dependency definition (from a Maven repo)
 */
case class DependencyLib(
    name : String,
    gav : GroupArtifactAndVersion,
    scope : String,
    classifierOpt : Option[String] = None) {

  val classifier = classifierOpt.getOrElse("")
  val version = gav.version.map(_.version).getOrElse("")

  override def toString = "Name: %s, GAV: %s, Classifier: %s".format(name, gav.toString, classifier)

  def toIvyMavenDependency : IvyMakePom#Dependency = {
    val ivyMakePom : IvyMakePom = new IvyMakePom
    val dep = new ivyMakePom.Dependency()
    dep.setGroup(gav.groupId.id)
    dep.setArtifact(gav.artifactId.id)
    dep.setVersion(version)
    dep.setScope(scope)
    dep.setOptional(false)
    dep
  }

  def toIvyPomWriterExtraDependencies : PomWriterOptions.ExtraDependency =
    new PomWriterOptions.ExtraDependency(gav.groupId.id, gav.artifactId.id, version, scope, false)
}

object DependencyLib {
  implicit def toIvyMavenDependency(lib : DependencyLib) : IvyMakePom#Dependency =
    lib.toIvyMavenDependency
  implicit def toIvyPomWriterExtraDependencies(lib : DependencyLib) : PomWriterOptions.ExtraDependency =
    lib.toIvyPomWriterExtraDependencies
}
