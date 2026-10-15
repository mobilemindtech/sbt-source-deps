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
 *   <id>/state     last published version, used to clean up stale artifacts
 */
final class Engine(root: File, commands: Map[BuildTool, Seq[String]], log: Logger):
  val repo: File = new File(root, "repo")

  /** Bump to invalidate every previously published artifact (e.g. when builder flags change). */
  private val formatVersion = "1"

  def resolve(dep: SourceDep, fetch: Boolean = false): Seq[Coordinates] =
    val home = new File(root, dep.id)
    Engine.withLock(home) {
      val projectDir = checkout(dep, home, fetch)
      val tool = dep.tool.orElse(Builders.detect(projectDir)).getOrElse {
        throw ProcessFailed(s"could not detect the build tool of $dep in $projectDir (use .buildTool(...))")
      }
      val fp = Hashing.fingerprint(projectDir, Seq(s"format=$formatVersion", s"tool=$tool"))
      val suffix = s"+src.${fp.take(12)}"

      val published = Poms.published(repo, suffix) match
        case Seq() =>
          log.info(s"[source-deps] building $dep with ${tool.toString.toLowerCase} (sources changed)")
          Builders.forTool(tool).publish(BuildRequest(projectDir, repo, home, suffix, commands, log, dep.id))
          val result = Poms.published(repo, suffix)
          if result.isEmpty then throw ProcessFailed(s"the build of $dep did not publish any module into $repo")
          removeStale(home, suffix)
          writeState(home, suffix)
          result
        case existing =>
          log.debug(s"[source-deps] $dep is up to date ($suffix)")
          existing

      select(dep, published)
    }

  /** Up-to-date check without building anything: Some(true) when fresh, None when not checked out yet. */
  def isUpToDate(dep: SourceDep): Option[Boolean] =
    val home = new File(root, dep.id)
    projectDir(dep, home).filter(_.isDirectory).map { dir =>
      val tool = dep.tool.orElse(Builders.detect(dir)).map(_.toString).getOrElse("?")
      val fp = Hashing.fingerprint(dir, Seq(s"format=$formatVersion", s"tool=$tool"))
      Poms.published(repo, s"+src.${fp.take(12)}").nonEmpty
    }

  def clean(dep: SourceDep): Unit =
    val home = new File(root, dep.id)
    readState(home).foreach(removeVersion)
    sbt.io.IO.delete(home)

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

  private def stateFile(home: File) = new File(home, "state")

  private def readState(home: File): Option[String] =
    val f = stateFile(home)
    if !f.isFile then None
    else
      val p = Properties()
      sbt.io.Using.fileInputStream(f)(p.load)
      Option(p.getProperty("suffix"))

  private def writeState(home: File, suffix: String): Unit =
    home.mkdirs()
    val p = Properties()
    p.setProperty("suffix", suffix)
    sbt.io.Using.fileOutputStream()(stateFile(home))(p.store(_, "sbt-source-deps"))

  private def removeStale(home: File, current: String): Unit =
    readState(home).filter(_ != current).foreach(removeVersion)

  /** Deletes the version directories published with `suffix` (group/artifact/<version>). */
  private def removeVersion(suffix: String): Unit =
    Poms.published(repo, suffix).foreach(c => sbt.io.IO.delete(versionDir(c)))

  private def versionDir(c: Coordinates): File =
    new File(repo, (c.groupId.split('.').toSeq :+ c.artifactId :+ c.version).mkString(File.separator))

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
