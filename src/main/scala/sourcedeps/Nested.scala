package sourcedeps

import java.io.File
import java.util.Properties
import scala.jdk.CollectionConverters.*

/**
 * Support for dependencies whose own build uses this plugin (app -> middle -> greeter).
 *
 * When the plugin builds an sbt dependency, it passes system properties to the nested sbt:
 *   - [[Nested.DirProp]]: the working directory of the outer build, used as the nested default of
 *     `sourceDepsDirectory`, so nested dependencies are built into the same repository;
 *   - [[Nested.RecordProp]]: a directory where the nested plugin records each source dependency it
 *     resolved, so the outer build can fingerprint, fetch and force them;
 *   - [[Nested.ChainProp]]: the ids of the dependencies being built, to detect cycles.
 */
private[sourcedeps] object Nested:
  val DirProp = "sbt.source-deps.dir"
  val RecordProp = "sbt.source-deps.record"
  val ChainProp = "sbt.source-deps.chain"

  /** Deeper chains are almost certainly a cycle the ids cannot reveal (same project, different locations). */
  val MaxDepth = 16

  /** A source dependency resolved by a nested build, with the Scala version and environment it was resolved for. */
  final case class Record(dep: SourceDep, scalaBinaryVersion: String, env: Map[String, String])

  def chain: Seq[String] = sys.props.get(ChainProp).toSeq.flatMap(_.split(',')).filter(_.nonEmpty)

  def recordDir(home: File, variant: String): File = new File(home, s"nested-$variant")

  /** Called by the nested plugin: records `dep` when the outer build asked for it. */
  def record(dep: SourceDep, scalaBinaryVersion: String, env: Map[String, String]): Unit =
    sys.props.get(RecordProp).foreach { dir =>
      val p = Properties()
      p.setProperty("id", dep.id)
      dep.location match
        case SourceLocation.Git(url, ref) =>
          p.setProperty("git.url", url)
          p.setProperty("git.ref", ref)
        case SourceLocation.Local(d) =>
          p.setProperty("local.dir", d.getCanonicalPath)
      if dep.partial then p.setProperty("git.partial", "true")
      dep.subdirectory.foreach(p.setProperty("subdir", _))
      dep.tool.foreach(t => p.setProperty("tool", t.toString))
      if dep.moduleFilter.nonEmpty then p.setProperty("modules", dep.moduleFilter.mkString(","))
      p.setProperty("scalaBinaryVersion", scalaBinaryVersion)
      env.foreach((k, v) => p.setProperty(s"env.$k", v))
      val out = new File(dir, s"${dep.id}-$scalaBinaryVersion.properties")
      out.getParentFile.mkdirs()
      sbt.io.Using.fileOutputStream()(out)(p.store(_, "sbt-source-deps nested dependency"))
    }

  /** Dependencies recorded by the last build of a dependency. */
  def read(home: File, variant: String): Seq[Record] =
    Option(recordDir(home, variant).listFiles()).getOrElse(Array.empty[File])
      .filter(_.getName.endsWith(".properties"))
      .sortBy(_.getName)
      .toSeq
      .flatMap { f =>
        val p = Properties()
        sbt.io.Using.fileInputStream(f)(p.load)
        def get(k: String) = Option(p.getProperty(k)).filter(_.nonEmpty)
        val location =
          get("git.url").map(SourceLocation.Git(_, get("git.ref").getOrElse("HEAD")))
            .orElse(get("local.dir").map(d => SourceLocation.Local(new File(d))))
        for
          loc <- location
          id <- get("id")
          scala <- get("scalaBinaryVersion")
        yield Record(
          SourceDep(
            loc,
            subdirectory = get("subdir"),
            moduleFilter = get("modules").toSeq.flatMap(_.split(',')),
            tool = get("tool").flatMap(t => BuildTool.values.find(_.toString == t)),
            // the id computed by the nested build names its directory: keep it verbatim
            alias = Some(id),
            partial = get("git.partial").contains("true")
          ),
          scala,
          p.stringPropertyNames.asScala.toSeq.collect { case k if k.startsWith("env.") => k.stripPrefix("env.") -> p.getProperty(k) }.toMap
        )
      }
