package sourcedeps

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Element, Node}

final case class Coordinates(groupId: String, artifactId: String, version: String, packaging: String):
  override def toString: String = s"$groupId:$artifactId:$version"

private[sourcedeps] object Poms:
  /** All modules published into `repo` whose version ends with `versionSuffix`. */
  def published(repo: File, versionSuffix: String): Seq[Coordinates] =
    if !repo.isDirectory then Nil
    else
      sbt.io.PathFinder(repo).**(sbt.io.GlobFilter("*.pom")).get()
        .filter(_.getParentFile.getName.endsWith(versionSuffix))
        .flatMap(read)
        .filter(_.version.endsWith(versionSuffix))
        .distinct
        .sortBy(_.toString)

  def read(pom: File): Option[Coordinates] =
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(false)
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val root = f.newDocumentBuilder().parse(pom).getDocumentElement
    val parent = child(root, "parent")
    for
      artifact <- text(root, "artifactId")
      group <- text(root, "groupId").orElse(parent.flatMap(text(_, "groupId")))
      version <- text(root, "version").orElse(parent.flatMap(text(_, "version")))
    yield Coordinates(group, artifact, version, text(root, "packaging").getOrElse("jar"))

  private def child(e: Element, name: String): Option[Element] =
    val nodes = e.getChildNodes
    (0 until nodes.getLength).iterator.map(nodes.item).collectFirst {
      case c: Element if c.getNodeType == Node.ELEMENT_NODE && c.getTagName == name => c
    }

  private def text(e: Element, name: String): Option[String] =
    child(e, name).map(_.getTextContent.trim).filter(_.nonEmpty)
