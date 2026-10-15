package sourcedeps

import java.io.File

enum BuildTool:
  case Sbt, Mill, Gradle, Maven, ScalaCli

enum SourceLocation:
  case Git(url: String, ref: String)
  case Local(dir: File)

/** A dependency built from sources: a git repository or a local directory. */
final case class SourceDep(
    location: SourceLocation,
    subdirectory: Option[String] = None,
    moduleFilter: Seq[String] = Nil,
    tool: Option[BuildTool] = None,
    alias: Option[String] = None,
    partial: Boolean = false
):
  /** Build the project located in this subdirectory of the source tree. */
  def subdir(path: String): SourceDep = copy(subdirectory = Some(path))

  /** Use only these published modules (artifactId, with or without the `_3`/`_2.13` suffix). */
  def modules(names: String*): SourceDep = copy(moduleFilter = names.toSeq)

  /** Skip detection and force a build tool. */
  def buildTool(t: BuildTool): SourceDep = copy(tool = Some(t))

  /**
   * Git only: clone without file contents (`--filter=blob:none`); git downloads them on checkout. Much
   * faster for large repositories with a long history, but checking out another ref needs the network.
   * The server must support partial clone (GitHub, GitLab and Bitbucket do); otherwise git makes a full
   * clone.
   */
  def partialClone: SourceDep = copy(partial = true)

  /** Name of the directory used for this dependency under `sourceDepsDirectory`. */
  def named(name: String): SourceDep = copy(alias = Some(name))

  def id: String = alias.getOrElse {
    val (base, identity) = location match
      case SourceLocation.Git(url, ref) =>
        (url.stripSuffix("/").split('/').last.stripSuffix(".git"), s"$url#$ref")
      case SourceLocation.Local(dir) =>
        (dir.getName, dir.getCanonicalPath)
    val full = identity + subdirectory.fold("")("/" + _)
    s"${sanitize(base)}-${Hashing.sha256(full).take(8)}"
  }

  override def toString: String =
    val where = location match
      case SourceLocation.Git(url, ref) => s"$url@$ref"
      case SourceLocation.Local(dir)    => dir.getPath
    where + subdirectory.fold("")(s => s" ($s)")

  private def sanitize(s: String): String = s.replaceAll("[^A-Za-z0-9._-]", "_")

object SourceDep:
  def git(url: String, ref: String = "HEAD"): SourceDep = SourceDep(SourceLocation.Git(url, ref))
  def local(dir: File): SourceDep = SourceDep(SourceLocation.Local(dir))
