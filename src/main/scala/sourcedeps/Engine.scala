package sourcedeps

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.{CREATE, WRITE}
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import sbt.util.Logger

/**
 * Layout under the root directory:
 *   repo/          isolated Maven repository shared by all source dependencies
 *   <id>/src/      git clone (git dependencies only)
 *   <id>/state-*   last published version per variant, used to clean up stale artifacts
 */
final case class Resolved(selected: Seq[Coordinates], published: Seq[Coordinates])

final class Engine(
    root: File,
    commands: Map[BuildTool, Seq[String]],
    env: Map[String, String],
    scalaBinaryVersion: String,
    log: Logger
):
  val repo: File = new File(root, "repo")

  /** Bump to invalidate every previously published artifact (e.g. when builder flags change). */
  private val formatVersion = "2"

  /** Builds `dep` when stale; returns the selected modules and every module the build published. */
  def resolve(dep: SourceDep, fetch: Boolean = false): Resolved =
    val home = new File(root, dep.id)
    Engine.withLock(home) {
      val projectDir = checkout(dep, home, fetch)
      val tool = detect(dep, projectDir).getOrElse {
        throw ProcessFailed(s"could not detect the build tool of $dep in $projectDir (use .buildTool(...))")
      }
      val (suffix, variant) = versionSuffix(projectDir, tool)

      val published = Poms.published(repo, suffix) match
        case Seq() =>
          log.info(s"[source-deps] building $dep with ${tool.toString.toLowerCase} (sources changed)")
          val req = BuildRequest(projectDir, repo, home, suffix, scalaBinaryVersion, commands, env, log, dep.id)
          Builders.forTool(tool).publish(req)
          val result = Poms.published(repo, suffix)
          if result.isEmpty then throw ProcessFailed(s"the build of $dep did not publish any module into $repo")
          readState(home, variant).filter(_ != suffix).foreach(removeVersion)
          writeState(home, variant, suffix)
          result
        case existing =>
          log.debug(s"[source-deps] $dep is up to date ($suffix)")
          existing

      Resolved(select(dep, published), published)
    }

  /** Up-to-date check without building anything: Some(true) when fresh, None when not checked out yet. */
  def isUpToDate(dep: SourceDep): Option[Boolean] =
    val home = new File(root, dep.id)
    projectDir(dep, home).filter(_.isDirectory).map { dir =>
      detect(dep, dir).exists(tool => Poms.published(repo, versionSuffix(dir, tool)._1).nonEmpty)
    }

  def clean(dep: SourceDep): Unit =
    val home = new File(root, dep.id)
    Option(home.listFiles()).getOrElse(Array.empty[File])
      .filter(_.getName.startsWith("state-"))
      .foreach(f => readState(home, f.getName.stripPrefix("state-")).foreach(removeVersion))
    sbt.io.IO.delete(home)

  private def detect(dep: SourceDep, dir: File): Option[BuildTool] = dep.tool.orElse(Builders.detect(dir))

  /**
   * The suffix appended to every published version, derived from the sources. sbt builds pick the
   * consumer's Scala version, so they get one variant per Scala binary version.
   */
  private def versionSuffix(dir: File, tool: BuildTool): (String, String) =
    val variant = if tool == BuildTool.Sbt then s"scala-$scalaBinaryVersion" else "default"
    val fp = Hashing.fingerprint(dir, Seq(s"format=$formatVersion", s"tool=$tool", s"variant=$variant"))
    (s"+src.${fp.take(12)}", variant)

  private def projectDir(dep: SourceDep, home: File): Option[File] =
    val base = dep.location match
      case SourceLocation.Git(_, _)  => new File(home, "src")
      case SourceLocation.Local(dir) => dir
    Some(dep.subdirectory.fold(base)(new File(base, _)))

  private def checkout(dep: SourceDep, home: File, fetch: Boolean): File =
    dep.location match
      case SourceLocation.Git(url, ref) => Git.sync(url, ref, new File(home, "src"), fetch, log)
      case SourceLocation.Local(dir) =>
        if !dir.isDirectory then throw ProcessFailed(s"source dependency directory does not exist: $dir")
    val dir = projectDir(dep, home).get
    if !dir.isDirectory then throw ProcessFailed(s"subdirectory not found: $dir")
    dir

  private def select(dep: SourceDep, all: Seq[Coordinates]): Seq[Coordinates] =
    val candidates = all.filterNot(_.packaging == "pom")
    if dep.moduleFilter.isEmpty then candidates
    else
      val selected = candidates.filter(c => dep.moduleFilter.contains(c.artifactId) || dep.moduleFilter.contains(baseName(c.artifactId)))
      val missing = dep.moduleFilter.filterNot(m => selected.exists(c => c.artifactId == m || baseName(c.artifactId) == m))
      if missing.nonEmpty then
        throw ProcessFailed(s"modules ${missing.mkString(", ")} not published by $dep; available: ${candidates.map(_.artifactId).mkString(", ")}")
      selected

  /** `core_3` -> `core`, `core_2.13` -> `core`, `plugin_2.12_1.0` -> `plugin`. */
  private def baseName(artifactId: String): String = artifactId.replaceAll("(_[0-9][0-9.]*(-[A-Za-z0-9]+)?)+$", "")

  private def stateFile(home: File, variant: String) = new File(home, s"state-$variant")

  private def readState(home: File, variant: String): Option[String] =
    val f = stateFile(home, variant)
    if !f.isFile then None
    else
      val p = Properties()
      sbt.io.Using.fileInputStream(f)(p.load)
      Option(p.getProperty("suffix"))

  private def writeState(home: File, variant: String, suffix: String): Unit =
    home.mkdirs()
    val p = Properties()
    p.setProperty("suffix", suffix)
    sbt.io.Using.fileOutputStream()(stateFile(home, variant))(p.store(_, "sbt-source-deps"))

  /** Deletes the version directories published with `suffix` (group/artifact/<version>). */
  private def removeVersion(suffix: String): Unit =
    Poms.published(repo, suffix).foreach { c =>
      val dir = Republish.versionDir(repo, c)
      sbt.io.IO.delete(dir)
      prune(dir.getParentFile)
    }

  /** Deletes artifact and group directories left without any version directory (metadata files only). */
  private def prune(dir: File): Unit =
    val base = repo.getCanonicalFile
    var current = dir.getCanonicalFile
    while current != base && current.isDirectory && !Option(current.listFiles()).getOrElse(Array.empty[File]).exists(_.isDirectory) do
      sbt.io.IO.delete(current)
      current = current.getParentFile

object Engine:
  private val jvmLocks = ConcurrentHashMap[String, ReentrantLock]()

  /** Serializes work on a dependency across threads of this JVM and across sbt processes. */
  def withLock[A](dir: File)(body: => A): A =
    dir.mkdirs()
    val key = dir.getCanonicalPath
    val lock = jvmLocks.computeIfAbsent(key, _ => ReentrantLock())
    lock.lock()
    try
      val channel = FileChannel.open(new File(dir, ".lock").toPath, CREATE, WRITE)
      try
        val fileLock = channel.lock()
        try body
        finally fileLock.release()
      finally channel.close()
    finally lock.unlock()
