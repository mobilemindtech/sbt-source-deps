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
 *   <id>/nested-*  source dependencies of the dependency's own build (see [[Nested]])
 */
final case class Resolved(selected: Seq[Coordinates], published: Seq[Coordinates])

final class Engine(
    root: File,
    commands: Map[BuildTool, Seq[String]],
    env: Map[String, String],
    scalaBinaryVersion: String,
    log: Logger,
    showOutput: Boolean = false
):
  val repo: File = new File(root, "repo")

  /** Bump to invalidate every previously published artifact (e.g. when builder flags change). */
  private val formatVersion = "3"

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
      val variant = variantOf(tool, scalaBinaryVersion)
      if fetch then fetchNested(home, variant, Set(dep.id))
      val own = ownFingerprint(dep, projectDir, tool, variant, env)
      val expected = suffixOf(own, nestedLines(home, variant, Set(dep.id)))

      val published = Poms.published(repo, expected) match
        case Seq() =>
          val reason = if readState(home, variant).isEmpty then "first build" else "changed since the last build"
          log.info(s"[source-deps] building $dep with ${tool.toString.toLowerCase} ($reason)")
          val nestedDir = Nested.recordDir(home, variant)
          sbt.io.IO.delete(nestedDir)
          val staging = new File(home, "staging")
          sbt.io.IO.delete(staging)
          staging.mkdirs()
          val req = BuildRequest(
            projectDir, staging, home, scalaBinaryVersion, commands, env ++ dep.extraEnv, log, dep.id,
            NestedContext(root, nestedDir, chain :+ dep.id), dep.extraArgs, showOutput
          )
          val started = System.nanoTime()
          Builders.forTool(tool).publish(req)
          // the build may have recorded nested source dependencies (first build, or a changed set):
          // the suffix is final only now, so later up-to-date checks compute the same value
          val suffix = suffixOf(own, nestedLines(home, variant, Set(dep.id)))
          val result = Republish(staging, repo, suffix)
          if result.isEmpty then
            throw ProcessFailed(s"the build of $dep did not publish any module into $staging.\n${Engine.nothingPublishedHint(tool)}")
          readState(home, variant).filter(_ != suffix).foreach(removeVersion)
          writeState(home, variant, suffix)
          val seconds = (System.nanoTime() - started) / 1000000000L
          log.info(s"[source-deps] built $dep in ${Engine.duration(seconds)}: ${result.size} module(s), version suffix $suffix")
          Poms.published(repo, suffix)
        case existing =>
          log.debug(s"[source-deps] $dep is up to date ($expected)")
          existing

      // modules of nested source dependencies come transitively: force them too
      val transitive = nestedPublished(home, variant, Set(dep.id))
      Resolved(select(dep, published), (published ++ transitive).distinct)
    }

  /** Up-to-date check without building anything: Some(true) when fresh, None when not checked out yet. */
  def isUpToDate(dep: SourceDep): Option[Boolean] =
    val home = new File(root, dep.id)
    projectDir(dep, home).filter(_.isDirectory).map { dir =>
      detect(dep, dir).exists { tool =>
        Poms.published(repo, versionSuffix(dep, dir, tool, scalaBinaryVersion, env, Set(dep.id))).nonEmpty
      }
    }

  def clean(dep: SourceDep): Unit =
    val home = new File(root, dep.id)
    Option(home.listFiles()).getOrElse(Array.empty[File])
      .filter(_.getName.startsWith("state-"))
      .foreach(f => readState(home, f.getName.stripPrefix("state-")).foreach(removeVersion))
    sbt.io.IO.delete(home)

  private def detect(dep: SourceDep, dir: File): Option[BuildTool] = dep.tool.orElse(Builders.detect(dir))

  /** sbt builds pick the consumer's Scala version, so they get one variant per Scala binary version. */
  private def variantOf(tool: BuildTool, scalaBinary: String): String =
    if tool == BuildTool.Sbt then s"scala-$scalaBinary" else "default"

  /**
   * The environment and extra arguments are part of it: a different `JAVA_HOME`, Maven profile, or any
   * variable the build reads rebuilds the dependency.
   */
  private def ownFingerprint(dep: SourceDep, dir: File, tool: BuildTool, variant: String, baseEnv: Map[String, String]): String =
    val envLines = (baseEnv ++ dep.extraEnv).toSeq.sorted.map((k, v) => s"env=$k=$v")
    val argLines = dep.extraArgs.map(a => s"arg=$a")
    Hashing.fingerprint(dir, Seq(s"format=$formatVersion", s"tool=$tool", s"variant=$variant") ++ envLines ++ argLines)

  /**
   * The suffix appended to every published version: the dependency's own sources plus, recursively,
   * the current suffix of each nested source dependency its last build recorded. A change deep down the
   * chain changes every suffix above it.
   */
  private def suffixOf(ownFingerprint: String, nested: Seq[String]): String =
    s"+src.${Hashing.sha256((ownFingerprint +: nested).mkString("\n")).take(12)}"

  private def nestedLines(home: File, variant: String, visited: Set[String]): Seq[String] =
    Nested.read(home, variant).map(r => s"nested=${r.dep.id}@${currentSuffix(r, visited).getOrElse("missing")}")

  private def versionSuffix(
      dep: SourceDep, dir: File, tool: BuildTool, scalaBinary: String, env: Map[String, String], visited: Set[String]
  ): String =
    val variant = variantOf(tool, scalaBinary)
    suffixOf(ownFingerprint(dep, dir, tool, variant, env), nestedLines(new File(root, dep.id), variant, visited))

  /** The suffix a nested dependency would be published with now; None when it is not checked out. */
  private def currentSuffix(r: Nested.Record, visited: Set[String]): Option[String] =
    if visited(r.dep.id) then Some("cycle")
    else
      for
        dir <- projectDir(r.dep, new File(root, r.dep.id)).filter(_.isDirectory)
        tool <- detect(r.dep, dir)
      yield versionSuffix(r.dep, dir, tool, r.scalaBinaryVersion, r.env, visited + r.dep.id)

  /** Every module currently published by the nested dependencies of `home`, recursively. */
  private def nestedPublished(home: File, variant: String, visited: Set[String]): Seq[Coordinates] =
    Nested.read(home, variant).filterNot(r => visited(r.dep.id)).flatMap { r =>
      val nestedHome = new File(root, r.dep.id)
      val own = currentSuffix(r, visited).toSeq.flatMap(Poms.published(repo, _))
      val tool = projectDir(r.dep, nestedHome).filter(_.isDirectory).flatMap(detect(r.dep, _))
      own ++ tool.toSeq.flatMap(t => nestedPublished(nestedHome, variantOf(t, r.scalaBinaryVersion), visited + r.dep.id))
    }

  /** `sourceDepsUpdate` also moves the branches of nested git dependencies, so their changes show up. */
  private def fetchNested(home: File, variant: String, visited: Set[String]): Unit =
    Nested.read(home, variant).filterNot(r => visited(r.dep.id)).foreach { r =>
      val nestedHome = new File(root, r.dep.id)
      Engine.withLock(nestedHome) {
        r.dep.location match
          case SourceLocation.Git(url, ref) =>
            Git.sync(url, ref, new File(nestedHome, "src"), fetch = true, log, r.dep.partial)
          case SourceLocation.Local(_)      => ()
      }
      projectDir(r.dep, nestedHome).filter(_.isDirectory).flatMap(detect(r.dep, _)).foreach { t =>
        fetchNested(nestedHome, variantOf(t, r.scalaBinaryVersion), visited + r.dep.id)
      }
    }

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
