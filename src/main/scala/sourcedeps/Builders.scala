package sourcedeps

import java.io.File
import sbt.util.Logger

/** Everything a builder needs to publish a project into the isolated Maven repository. */
final case class BuildRequest(
    projectDir: File,
    repo: File,
    workDir: File,
    versionSuffix: String,
    commands: Map[BuildTool, Seq[String]],
    log: Logger,
    label: String
)

trait Builder:
  def publish(req: BuildRequest): Unit

object Builders:
  /** Detection order matters: a mill/sbt project may also carry a pom.xml or build.gradle for other users. */
  def detect(dir: File): Option[BuildTool] =
    def has(names: String*) = names.exists(n => new File(dir, n).exists)
    if has("build.mill", "build.mill.scala", "build.sc") then Some(BuildTool.Mill)
    else if has("build.sbt", "project/build.properties") then Some(BuildTool.Sbt)
    else if has("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") then Some(BuildTool.Gradle)
    else if has("pom.xml") then Some(BuildTool.Maven)
    else if has("project.scala") then Some(BuildTool.ScalaCli)
    else None

  def forTool(tool: BuildTool): Builder = tool match
    case BuildTool.Sbt => SbtBuilder
    case other         => (_: BuildRequest) => throw ProcessFailed(s"$other builder is not implemented yet")

  /** Default command used to launch each tool when the project has no wrapper. */
  val defaultCommands: Map[BuildTool, Seq[String]] = Map(
    BuildTool.Sbt -> Seq("sbt"),
    BuildTool.Mill -> Seq("mill"),
    BuildTool.Gradle -> Seq("gradle"),
    BuildTool.Maven -> Seq("mvn"),
    BuildTool.ScalaCli -> Seq("scala-cli")
  )

  private[sourcedeps] def runLogged(req: BuildRequest, cmd: Seq[String], env: Seq[(String, String)] = Nil): Unit =
    req.log.info(s"[source-deps] ${req.label}: ${cmd.mkString(" ")}")
    val r = Proc.run(cmd, req.projectDir, env)(line => req.log.debug(s"[${req.label}] $line"))
    if !r.ok then
      r.out.takeRight(80).foreach(l => req.log.error(s"[${req.label}] $l"))
      throw ProcessFailed(s"build of ${req.label} failed (exit ${r.exitCode}) running: ${cmd.mkString(" ")}")

object SbtBuilder extends Builder:
  def publish(req: BuildRequest): Unit =
    val globalSettings = prepareGlobalSettings(req)
    val sbt = req.commands.getOrElse(BuildTool.Sbt, Builders.defaultCommands(BuildTool.Sbt))
    val cmd = sbt ++ Seq(
      "--server", "--batch", "--no-colors", "--supershell=never",
      s"-Dsbt.global.settings=${globalSettings.getAbsolutePath}",
      "publish"
    )
    Builders.runLogged(req, cmd)

  /**
   * `set every version ~= ...` is cyclic, and `set every version := ...` would lose each project's own
   * version, so the overrides are appended after load through a global settings file instead. The user's
   * own global `*.sbt` files are copied next to it so credentials/resolvers defined there keep working.
   */
  private def prepareGlobalSettings(req: BuildRequest): File =
    val dir = new File(req.workDir, "sbt-global-settings")
    sbt.io.IO.delete(dir)
    dir.mkdirs()
    val userDir = new File(sys.props("user.home"), s".sbt/${series(req.projectDir)}")
    Option(userDir.listFiles()).getOrElse(Array.empty[File])
      .filter(f => f.isFile && f.getName.endsWith(".sbt"))
      .foreach(f => sbt.io.IO.copyFile(f, new File(dir, f.getName)))
    sbt.io.IO.write(new File(dir, "zz-source-deps.sbt"), overrides(req.versionSuffix, req.repo.toURI.toString))
    dir

  /** Global settings directory name used by the sbt version of the project (`1.0` or `2`). */
  private def series(projectDir: File): String =
    val props = new File(projectDir, "project/build.properties")
    val version =
      if !props.isFile then None
      else
        val p = java.util.Properties()
        sbt.io.Using.fileInputStream(props)(p.load)
        Option(p.getProperty("sbt.version")).map(_.trim)
    if version.exists(_.startsWith("1.")) || version.exists(_.startsWith("0.")) then "1.0" else "2"

  /** Must compile on both sbt 1.x (Scala 2.12) and sbt 2.x (Scala 3). Idempotent: global settings may be applied per project. */
  private def overrides(suffix: String, repoUri: String): String =
    s"""Global / onLoad := {
       |  val previous = (Global / onLoad).value
       |  previous.andThen { state =>
       |    val extracted = Project.extract(state)
       |    val refs = extracted.structure.allProjectRefs
       |    // appendWithSession reloads the session, which runs onLoad again: stop once applied
       |    if (refs.forall(ref => extracted.get(ref / version).endsWith("$suffix"))) state
       |    else {
       |      val settings = refs.flatMap { ref =>
       |        Seq(
       |          ref / version := { val v = (ref / version).value; if (v.endsWith("$suffix")) v else v + "$suffix" },
       |          ref / publishTo := Some("source-deps" at "$repoUri"),
       |          ref / publishMavenStyle := true,
       |          ref / Compile / packageDoc / publishArtifact := false
       |        )
       |      }
       |      extracted.appendWithSession(settings, state)
       |    }
       |  }
       |}
       |""".stripMargin
