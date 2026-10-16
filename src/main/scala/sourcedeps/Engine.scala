package sourcedeps

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.{CREATE, WRITE}
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import sbt.util.Logger

/**
 * Layout under the root directory (per project, or shared by every project of the machine):
 *   repo/          isolated Maven repository shared by all source dependencies
 *   repo/.index/   one file per published suffix, written last: a version exists once its index does
 *   <id>/src/      git clone (git dependencies only)
 *   <id>/state-*   last published version per slot, used to clean up stale artifacts
 *   <id>/nested-*  source dependencies of the dependency's own build (see [[Nested]])
 *   <id>/file-hashes.tsv  per-file hashes reused while size and mtime do not change (see [[HashCache]])
 *
 * A slot is the variant (Scala binary version for sbt builds) plus the extra environment and
 * arguments: consumers building one dependency with different options keep one version each instead
 * of deleting each other's. Writes to `repo/` happen under its lock, since other sbt processes may
 * share it.
 */
final case class Resolved(selected: Seq[Coordinates], published: Seq[Coordinates])

final class Engine(
    root: File,
    commands: Map[BuildTool, Seq[String]],
    env: Map[String, String],
    scalaBinaryVersion: String,
    scalaVersion: String,
    log: Logger,
    showOutput: Boolean = false
):
  val repo: File = new File(root, "repo")

  /** Bump to invalidate every previously published artifact (e.g. when builder flags change). */
  private val formatVersion = "6"

  /**
   * Builds `dep` when stale; returns the selected modules and every module to force (the build's own
   * modules plus those of its nested source dependencies).
   */
  def resolve(dep: SourceDep, fetch: Boolean = false): Resolved =
    val chain = Nested.chain
    if chain.contains(dep.id) || chain.size >= Nested.MaxDepth then
      throw ProcessFailed(s"cycle between source dependencies: ${(chain :+ dep.id).mkString(" -> ")}")
    val home = new File(root, dep.id)
    Engine.withLock(home) {
      val projectDir = checkout(dep, home, fetch)
      val tool = detect(dep, projectDir).getOrElse {
        throw ProcessFailed(s"could not detect the build tool of $dep in $projectDir (use .buildTool(...))")
      }
      val variant = variantOf(tool, scalaVersion)
      val slot = slotOf(variant, dep, env)
      if fetch then fetchNested(home, slot, Set(dep.id))
      val own = ownFingerprint(dep, projectDir, tool, variant, env)
      val expected = suffixOf(own, nestedLines(home, slot, Set(dep.id)))

      val published = Poms.indexed(repo, expected) match
        case Seq() =>
          val reason =
            if readState(home, slot).nonEmpty then "changed since the last build"
            else if slots(home).nonEmpty then "first build with these options"
            else "first build"
          log.info(s"[source-deps] building $dep with ${tool.toString.toLowerCase} ($reason)")
          val nestedDir = Nested.recordDir(home, slot)
          sbt.io.IO.delete(nestedDir)
          val staging = new File(home, "staging")
          sbt.io.IO.delete(staging)
          staging.mkdirs()
          val req = BuildRequest(
            projectDir, staging, home, scalaBinaryVersion, scalaVersion, commands, env ++ dep.extraEnv, log, dep.id,
            NestedContext(root, nestedDir, chain :+ dep.id), dep.extraArgs, showOutput
          )
          val started = System.nanoTime()
          Builders.forTool(tool).publish(req)
          // the build may have recorded nested source dependencies (first build, or a changed set):
          // the suffix is final only now, so later up-to-date checks compute the same value
          val suffix = suffixOf(own, nestedLines(home, slot, Set(dep.id)))
          val result = Engine.withLock(repo) {
            val modules = Republish(staging, repo, suffix)
            if modules.nonEmpty then Poms.writeIndex(repo, suffix, modules)
            modules
          }
          if result.isEmpty then
            throw ProcessFailed(s"the build of $dep did not publish any module into $staging.\n${Engine.nothingPublishedHint(tool)}")
          readState(home, slot).filter(_ != suffix).foreach(removeVersion)
          writeState(home, slot, suffix)
          removeUnusedSlots(home)
          val seconds = (System.nanoTime() - started) / 1000000000L
          log.info(s"[source-deps] built $dep in ${Engine.duration(seconds)}: ${result.size} module(s), version suffix $suffix")
          result
        case existing =>
          log.debug(s"[source-deps] $dep is up to date ($expected)")
          stateFile(home, slot).setLastModified(System.currentTimeMillis())
          existing

      // modules of nested source dependencies come transitively: force them too
      val transitive = nestedPublished(home, slot, Set(dep.id))
      Resolved(select(dep, published), (published ++ transitive).distinct)
    }

  /** Up-to-date check without building anything: Some(true) when fresh, None when not checked out yet. */
  def isUpToDate(dep: SourceDep): Option[Boolean] =
    val home = new File(root, dep.id)
    projectDir(dep, home).filter(_.isDirectory).map { dir =>
      detect(dep, dir).exists { tool =>
        Poms.indexed(repo, versionSuffix(dep, dir, tool, scalaVersion, env, Set(dep.id))).nonEmpty
      }
    }

  def clean(dep: SourceDep): Unit =
    val home = new File(root, dep.id)
    Option(home.listFiles()).getOrElse(Array.empty[File])
      .filter(_.getName.startsWith("state-"))
      .foreach(f => readState(home, f.getName.stripPrefix("state-")).foreach(removeVersion))
    sbt.io.IO.delete(home)

  private def detect(dep: SourceDep, dir: File): Option[BuildTool] = dep.tool.orElse(Builders.detect(dir))

  /**
   * sbt builds pick the consumer's Scala version (the exact one when they build for it: Scala 3 reads
   * TASTy only from versions up to its own), so they get one variant per Scala version.
   */
  private def variantOf(tool: BuildTool, scala: String): String =
    if tool == BuildTool.Sbt then s"scala-$scala" else "default"

  /** The variant, plus a hash of the extra environment and arguments when there are any. */
  private def slotOf(variant: String, dep: SourceDep, baseEnv: Map[String, String]): String =
    val config = configLines(dep, baseEnv)
    if config.isEmpty then variant else s"$variant-${Hashing.sha256(config.mkString("\n")).take(8)}"

  private def configLines(dep: SourceDep, baseEnv: Map[String, String]): Seq[String] =
    (baseEnv ++ dep.extraEnv).toSeq.sorted.map((k, v) => s"env=$k=$v") ++ dep.extraArgs.map(a => s"arg=$a")

  /**
   * The environment and extra arguments are part of it: a different `JAVA_HOME`, Maven profile, or any
   * variable the build reads rebuilds the dependency.
   */
  private def ownFingerprint(dep: SourceDep, dir: File, tool: BuildTool, variant: String, baseEnv: Map[String, String]): String =
    val cache = new File(new File(root, dep.id), "file-hashes.tsv")
    Hashing.fingerprint(dir, Seq(s"format=$formatVersion", s"tool=$tool", s"variant=$variant") ++ configLines(dep, baseEnv), Some(cache))

  /**
   * The suffix appended to every published version: the dependency's own sources plus, recursively,
   * the current suffix of each nested source dependency its last build recorded. A change deep down the
   * chain changes every suffix above it.
   */
  private def suffixOf(ownFingerprint: String, nested: Seq[String]): String =
    s"+src.${Hashing.sha256((ownFingerprint +: nested).mkString("\n")).take(12)}"

  private def nestedLines(home: File, slot: String, visited: Set[String]): Seq[String] =
    Nested.read(home, slot).map(r => s"nested=${r.dep.id}@${currentSuffix(r, visited).getOrElse("missing")}")

  private def versionSuffix(
      dep: SourceDep, dir: File, tool: BuildTool, scala: String, env: Map[String, String], visited: Set[String]
  ): String =
    val variant = variantOf(tool, scala)
    suffixOf(ownFingerprint(dep, dir, tool, variant, env), nestedLines(new File(root, dep.id), slotOf(variant, dep, env), visited))

  /** The suffix a nested dependency would be published with now; None when it is not checked out. */
  private def currentSuffix(r: Nested.Record, visited: Set[String]): Option[String] =
    if visited(r.dep.id) then Some("cycle")
    else
      for
        dir <- projectDir(r.dep, new File(root, r.dep.id)).filter(_.isDirectory)
        tool <- detect(r.dep, dir)
      yield versionSuffix(r.dep, dir, tool, r.scalaVersion, r.env, visited + r.dep.id)

  /** Every module currently published by the nested dependencies of `home`, recursively. */
  private def nestedPublished(home: File, slot: String, visited: Set[String]): Seq[Coordinates] =
    Nested.read(home, slot).filterNot(r => visited(r.dep.id)).flatMap { r =>
      val nestedHome = new File(root, r.dep.id)
      val own = currentSuffix(r, visited).toSeq.flatMap(Poms.indexed(repo, _))
      val tool = projectDir(r.dep, nestedHome).filter(_.isDirectory).flatMap(detect(r.dep, _))
      own ++ tool.toSeq.flatMap(t => nestedPublished(nestedHome, nestedSlot(t, r), visited + r.dep.id))
    }

  /** `sourceDepsUpdate` also moves the branches of nested git dependencies, so their changes show up. */
  private def fetchNested(home: File, slot: String, visited: Set[String]): Unit =
    Nested.read(home, slot).filterNot(r => visited(r.dep.id)).foreach { r =>
      val nestedHome = new File(root, r.dep.id)
      Engine.withLock(nestedHome) {
        r.dep.location match
          case SourceLocation.Git(url, ref) =>
            Git.sync(url, ref, new File(nestedHome, "src"), fetch = true, log, r.dep.partial)
          case SourceLocation.Local(_)      => ()
      }
      projectDir(r.dep, nestedHome).filter(_.isDirectory).flatMap(detect(r.dep, _)).foreach { t =>
        fetchNested(nestedHome, nestedSlot(t, r), visited + r.dep.id)
      }
    }

  /** The slot a nested build used for its dependency: computed with that build's own `sourceDepsEnv`. */
  private def nestedSlot(tool: BuildTool, r: Nested.Record): String =
    slotOf(variantOf(tool, r.scalaVersion), r.dep, r.env)

  private def projectDir(dep: SourceDep, home: File): Option[File] =
    val base = dep.location match
      case SourceLocation.Git(_, _)  => new File(home, "src")
      case SourceLocation.Local(dir) => dir
    Some(dep.subdirectory.fold(base)(new File(base, _)))

  private def checkout(dep: SourceDep, home: File, fetch: Boolean): File =
    dep.location match
      case SourceLocation.Git(url, ref) => Git.sync(url, ref, new File(home, "src"), fetch, log, dep.partial)
      case SourceLocation.Local(dir) =>
        if !dir.isDirectory then throw ProcessFailed(s"source dependency directory does not exist: $dir")
    val dir = projectDir(dep, home).get
    if !dir.isDirectory then throw ProcessFailed(s"subdirectory not found: $dir")
    dir

  private def select(dep: SourceDep, all: Seq[Coordinates]): Seq[Coordinates] =
    val candidates = forThisScala(all.filterNot(_.packaging == "pom"))
    if dep.moduleFilter.isEmpty then candidates
    else
      val selected = candidates.filter(c => dep.moduleFilter.contains(c.artifactId) || dep.moduleFilter.contains(baseName(c.artifactId)))
      val missing = dep.moduleFilter.filterNot(m => selected.exists(c => c.artifactId == m || baseName(c.artifactId) == m))
      if missing.nonEmpty then
        throw ProcessFailed(s"modules ${missing.mkString(", ")} not published by $dep; available: ${candidates.map(_.artifactId).mkString(", ")}")
      selected

  /**
   * Builds that cross-publish every Scala version at once (Mill `Cross`, Gradle/Maven variants) give
   * `core_2.13` and `core_3`: keep the one matching the consumer. A module without an artifact for this
   * Scala binary version is kept whole (e.g. a 2.13 library used from Scala 3).
   */
  private def forThisScala(modules: Seq[Coordinates]): Seq[Coordinates] =
    modules.groupBy(c => (c.groupId, baseName(c.artifactId))).values.flatMap { group =>
      val matching = group.filter(c => scalaSuffix(c.artifactId).contains(scalaBinaryVersion))
      if matching.isEmpty then group else matching ++ group.filter(c => scalaSuffix(c.artifactId).isEmpty)
    }.toSeq.sortBy(_.toString)

  private def scalaSuffix(artifactId: String): Option[String] =
    "_([0-9]+(?:\\.[0-9]+)?)$".r.findFirstMatchIn(artifactId).map(_.group(1))

  /** `core_3` -> `core`, `core_2.13` -> `core`, `plugin_2.12_1.0` -> `plugin`. */
  private def baseName(artifactId: String): String = artifactId.replaceAll("(_[0-9][0-9.]*(-[A-Za-z0-9]+)?)+$", "")

  private def stateFile(home: File, slot: String) = new File(home, s"state-$slot")

  private def readState(home: File, slot: String): Option[String] =
    val f = stateFile(home, slot)
    if !f.isFile then None
    else
      val p = Properties()
      sbt.io.Using.fileInputStream(f)(p.load)
      Option(p.getProperty("suffix"))

  private def writeState(home: File, slot: String, suffix: String): Unit =
    home.mkdirs()
    val p = Properties()
    p.setProperty("suffix", suffix)
    sbt.io.Using.fileOutputStream()(stateFile(home, slot))(p.store(_, "sbt-source-deps"))

  private def slots(home: File): Seq[String] =
    Option(home.listFiles()).getOrElse(Array.empty[File]).toSeq.map(_.getName).filter(_.startsWith("state-")).map(_.stripPrefix("state-"))

  /**
   * Versions built for other options or Scala versions are kept while some build still uses them (each
   * up-to-date check touches its state file); those unused for [[Engine.unusedSlotDays]] days go.
   */
  private def removeUnusedSlots(home: File): Unit =
    val limit = System.currentTimeMillis() - Engine.unusedSlotDays * 24L * 3600 * 1000
    slots(home).filter(slot => stateFile(home, slot).lastModified < limit).foreach { slot =>
      readState(home, slot).foreach(removeVersion)
      sbt.io.IO.delete(Seq(stateFile(home, slot), Nested.recordDir(home, slot)))
    }

  /** Deletes the version directories published with `suffix` (group/artifact/<version>), index first. */
  private def removeVersion(suffix: String): Unit =
    Engine.withLock(repo) {
      val modules = Poms.indexed(repo, suffix)
      Poms.deleteIndex(repo, suffix)
      modules.foreach { c =>
        val dir = Republish.versionDir(repo, c)
        sbt.io.IO.delete(dir)
        prune(dir.getParentFile)
      }
    }

  /** Deletes artifact and group directories left without any version directory (metadata files only). */
  private def prune(dir: File): Unit =
    val base = repo.getCanonicalFile
    var current = dir.getCanonicalFile
    while current != base && current.isDirectory && !Option(current.listFiles()).getOrElse(Array.empty[File]).exists(_.isDirectory) do
      sbt.io.IO.delete(current)
      current = current.getParentFile

object Engine:
  val unusedSlotDays = 30

  def duration(seconds: Long): String =
    if seconds < 60 then s"$seconds s" else s"${seconds / 60} min ${seconds % 60} s"

  /** Common reasons why a build tool publishes nothing, by tool. */
  def nothingPublishedHint(tool: BuildTool): String = tool match
    case BuildTool.Sbt =>
      "Check that the projects you need do not set `publish / skip := true` and are aggregated by the root project."
    case BuildTool.Maven =>
      "Check that `maven.deploy.skip` is not set and that no plugin replaces maven-deploy-plugin (e.g. nexus-staging)."
    case BuildTool.Gradle =>
      "Only projects applying the `java` plugin (java-library, scala, kotlin-jvm, ...) are published."
    case BuildTool.Mill =>
      "Only modules extending `PublishModule` are published."
    case BuildTool.ScalaCli =>
      "Check the `//> using publish.*` directives of the project."
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
