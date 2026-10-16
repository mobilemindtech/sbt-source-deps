package sourcedeps

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Element, Node}

final case class Coordinates(groupId: String, artifactId: String, version: String, packaging: String):
  override def toString: String = s"$groupId:$artifactId:$version"

private[sourcedeps] object Poms:
  /**
   * All modules published into the Maven repository `repo` (the staging repository of a build) whose
   * version ends with `versionSuffix`.
   * Coordinates come from the repository layout (group/artifact/version/artifact-version.pom): raw
   * POMs may still hold placeholders such as `${revision}`; the POM only contributes the packaging.
   */
  def published(repo: File, versionSuffix: String): Seq[Coordinates] =
    if !repo.isDirectory then Nil
    else
      val root = repo.toPath
      sbt.io.PathFinder(repo).**(sbt.io.GlobFilter("*.pom")).get()
        .flatMap { pom =>
          val versionDir = pom.getParentFile
          val artifactDir = versionDir.getParentFile
          val version = versionDir.getName
          val artifact = artifactDir.getName
          val groupPath = root.relativize(artifactDir.getParentFile.toPath).toString
          if !version.endsWith(versionSuffix) || pom.getName != s"$artifact-$version.pom" || groupPath.isEmpty then None
          else
            val group = groupPath.replace(File.separatorChar, '.')
            Some(Coordinates(group, artifact, version, read(pom).map(_.packaging).getOrElse("jar")))
        }
        .distinct
        .sortBy(_.toString)

  /**
   * Modules of a version published into the isolated repository, from its index (`repo/.index/<suffix>`).
   * The index is written after every file of the version and deleted before them, so a version that
   * another sbt process is still writing or removing is never seen half-done. Scanning the whole
   * repository for POMs would also grow with every project sharing it.
   */
  def indexed(repo: File, suffix: String): Seq[Coordinates] =
    val f = indexFile(repo, suffix)
    if !f.isFile then Nil
    else
      sbt.io.IO.readLines(f).filter(_.nonEmpty).map { line =>
        val Array(group, artifact, version, packaging) = line.split('\t'): @unchecked
        Coordinates(group, artifact, version, packaging)
      }

  def writeIndex(repo: File, suffix: String, modules: Seq[Coordinates]): Unit =
    val f = indexFile(repo, suffix)
    f.getParentFile.mkdirs()
    val tmp = new File(f.getParentFile, s".${f.getName}.tmp")
    sbt.io.IO.writeLines(tmp, modules.sortBy(_.toString).map(c => Seq(c.groupId, c.artifactId, c.version, c.packaging).mkString("\t")))
    java.nio.file.Files.move(tmp.toPath, f.toPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)

  def deleteIndex(repo: File, suffix: String): Unit = sbt.io.IO.delete(indexFile(repo, suffix))

  private def indexFile(repo: File, suffix: String): File = new File(new File(repo, ".index"), suffix)

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
