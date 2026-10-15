package sourcedeps

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.{OutputKeys, TransformerFactory}
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.{Document, Element, Node}

/**
 * Copies modules published with their original version into the isolated repository, appending
 * the fingerprint suffix to the version. POMs are rewritten so the module, its parent and its
 * dependencies on sibling modules all point at the suffixed versions; checksums are regenerated.
 */
private[sourcedeps] object Republish:
  private val skipped = Seq(".sha1", ".md5", ".sha256", ".sha512", ".asc", ".module")

  def apply(staging: File, repo: File, suffix: String): Seq[Coordinates] =
    val staged = Poms.published(staging, "").filterNot(_.version.endsWith(suffix))
    val reactor = staged.map(c => (c.groupId, c.artifactId) -> c.version).toMap
    staged.map { c =>
      val from = versionDir(staging, c)
      val target = c.copy(version = c.version + suffix)
      val to = versionDir(repo, target)
      sbt.io.IO.delete(to)
      to.mkdirs()
      val prefix = s"${c.artifactId}-${c.version}"
      Option(from.listFiles()).getOrElse(Array.empty[File])
        .filter(f => f.isFile && f.getName.startsWith(prefix) && !skipped.exists(f.getName.endsWith))
        .foreach { f =>
          val out = new File(to, s"${c.artifactId}-${target.version}" + f.getName.stripPrefix(prefix))
          if f.getName.endsWith(".pom") then rewritePom(f, out, c, reactor, suffix)
          else Files.copy(f.toPath, out.toPath)
          writeChecksums(out)
        }
      target
    }

  private def rewritePom(in: File, out: File, c: Coordinates, reactor: Map[(String, String), String], suffix: String): Unit =
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val doc = factory.newDocumentBuilder().parse(in)
    val project = doc.getDocumentElement

    // the module version may be inherited from the parent: always make it explicit
    child(project, "version") match
      case Some(v) => v.setTextContent(c.version + suffix)
      case None =>
        val v = doc.createElement("version")
        v.setTextContent(c.version + suffix)
        project.insertBefore(v, child(project, "artifactId").map(_.getNextSibling).orNull)

    child(project, "parent").foreach(bumpIfSibling(_, reactor, suffix))
    descendants(project, "dependency").foreach(bumpIfSibling(_, reactor, suffix))
    write(doc, out)

  /** Appends the suffix to a parent/dependency version when it references a module of the same build. */
  private def bumpIfSibling(e: Element, reactor: Map[(String, String), String], suffix: String): Unit =
    for
      g <- text(e, "groupId")
      a <- text(e, "artifactId")
      original <- reactor.get((g, a))
      v <- child(e, "version")
      text = v.getTextContent.trim
      // literal sibling version, or a placeholder such as ${revision} that only the build could resolve
      if text == original || (text.startsWith("${") && text != "${project.version}")
    do v.setTextContent(original + suffix)

  private def write(doc: Document, out: File): Unit =
    val t = TransformerFactory.newInstance().newTransformer()
    t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    t.transform(DOMSource(doc), StreamResult(out))

  private def writeChecksums(f: File): Unit =
    val bytes = Files.readAllBytes(f.toPath)
    for (algo, ext) <- Seq("SHA-1" -> "sha1", "MD5" -> "md5") do
      sbt.io.IO.write(new File(f.getPath + "." + ext), Hashing.hex(MessageDigest.getInstance(algo).digest(bytes)))

  private[sourcedeps] def versionDir(repo: File, c: Coordinates): File =
    new File(repo, (c.groupId.split('.').toSeq :+ c.artifactId :+ c.version).mkString(File.separator))

  private def child(e: Element, name: String): Option[Element] =
    val nodes = e.getChildNodes
    (0 until nodes.getLength).iterator.map(nodes.item).collectFirst {
      case c: Element if c.getNodeType == Node.ELEMENT_NODE && c.getTagName == name => c
    }

  private def descendants(e: Element, name: String): Seq[Element] =
    val nodes = e.getElementsByTagName(name)
    (0 until nodes.getLength).map(nodes.item).collect { case c: Element => c }

  private def text(e: Element, name: String): Option[String] =
    child(e, name).map(_.getTextContent.trim).filter(_.nonEmpty)
