package sourcedeps

import java.io.File
import sbt.util.Logger

/** Everything a builder needs to publish a project into the isolated Maven repository. */
final case class BuildRequest(
    projectDir: File,
    repo: File,
    workDir: File,
    versionSuffix: String,
    scalaBinaryVersion: String,
    commands: Map[BuildTool, Seq[String]],
    env: Map[String, String],
    log: Logger,
    label: String
)

trait Builder:
  /** Publishes every module of the project into `req.repo`, with versions ending in `req.versionSuffix`. */
  def publish(req: BuildRequest): Unit

/** Builders that can only publish with the project's own version: they publish into a staging repository first. */
abstract class StagingBuilder extends Builder:
  protected def publishTo(req: BuildRequest, staging: File): Unit

  final def publish(req: BuildRequest): Unit =
    val staging = new File(req.workDir, "staging")
    sbt.io.IO.delete(staging)
    staging.mkdirs()
    publishTo(req, staging)
    Republish(staging, req.repo, req.versionSuffix)

object Builders:
  /** Detection order matters: a mill/sbt project may also carry a pom.xml or build.gradle for other users. */
  def detect(dir: File): Option[BuildTool] =
    def has(names: String*) = names.exists(n => new File(dir, n).exists)
    if has("build.mill", "build.mill.scala", "build.mill.yaml", "build.sc") then Some(BuildTool.Mill)
    else if has("build.sbt", "project/build.properties") then Some(BuildTool.Sbt)
    else if has("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") then Some(BuildTool.Gradle)
    else if has("pom.xml") then Some(BuildTool.Maven)
    else if has("project.scala") then Some(BuildTool.ScalaCli)
    else None

  def forTool(tool: BuildTool): Builder = tool match
    case BuildTool.Sbt      => SbtBuilder
    case BuildTool.Maven    => MavenBuilder
    case BuildTool.Gradle   => GradleBuilder
    case BuildTool.Mill     => MillBuilder
    case BuildTool.ScalaCli => ScalaCliBuilder

  /** Command used to launch each tool when the project has no wrapper script. */
  val defaultCommands: Map[BuildTool, Seq[String]] = Map(
    BuildTool.Sbt -> Seq("sbt"),
    BuildTool.Mill -> Seq("mill"),
    BuildTool.Gradle -> Seq("gradle"),
    BuildTool.Maven -> Seq("mvn"),
    BuildTool.ScalaCli -> Seq("scala-cli")
  )

  /** The project's wrapper script when present (./gradlew, ./mvnw, ./mill), otherwise the configured command. */
  private[sourcedeps] def launcher(req: BuildRequest, tool: BuildTool, wrapper: Option[String]): Seq[String] =
    val script = wrapper.map(new File(req.projectDir, _)).filter(_.isFile)
    script match
      case Some(f) if f.canExecute => Seq(f.getAbsolutePath)
      case Some(f)                 => Seq("sh", f.getAbsolutePath)
      case None                    => req.commands.getOrElse(tool, defaultCommands(tool))

  private[sourcedeps] def onPath(command: String): Boolean =
    sys.env.getOrElse("PATH", "").split(File.pathSeparator).exists(d => new File(d, command).canExecute)

  private[sourcedeps] def runLogged(req: BuildRequest, cmd: Seq[String]): Unit =
    req.log.info(s"[source-deps] ${req.label}: ${cmd.mkString(" ")}")
    val r = Proc.run(cmd, req.projectDir, req.env.toSeq)(line => req.log.debug(s"[${req.label}] $line"))
    if !r.ok then
      r.out.takeRight(80).foreach(l => req.log.error(s"[${req.label}] $l"))
      throw ProcessFailed(s"build of ${req.label} failed (exit ${r.exitCode}) running: ${cmd.mkString(" ")}")

  private[sourcedeps] def fileUri(dir: File): String = dir.toURI.toString.replaceFirst("^file:/(?!/)", "file:///")

object SbtBuilder extends Builder:
  def publish(req: BuildRequest): Unit =
    val globalSettings = prepareGlobalSettings(req)
    val cmd = Builders.launcher(req, BuildTool.Sbt, None) ++ Seq(
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
    sbt.io.IO.write(
      new File(dir, "zz-source-deps.sbt"),
      overrides(req.versionSuffix, req.repo.toURI.toString, req.scalaBinaryVersion)
    )
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

  /**
   * Must compile on both sbt 1.x (Scala 2.12) and sbt 2.x (Scala 3). Global settings also reach the
   * meta-build, so every override is a constant read from the loaded build: deriving it from the key
   * itself (`(ref / scalaVersion).value`) creates cycles there. Besides the version and the repository,
   * each project switches to the entry of its `crossScalaVersions` matching the consumer's Scala binary
   * version, when there is one.
   */
  private def overrides(suffix: String, repoUri: String, scalaBinary: String): String =
    s"""Global / onLoad := {
       |  val previous = (Global / onLoad).value
       |  previous.andThen { state =>
       |    val extracted = Project.extract(state)
       |    val refs = extracted.structure.allProjectRefs
       |    // appendWithSession reloads the session, which runs onLoad again: stop once applied
       |    if (refs.forall(ref => extracted.get(ref / version).endsWith("$suffix"))) state
       |    else {
       |      def binary(v: String) = sbt.librarymanagement.CrossVersion.binaryScalaVersion(v)
       |      val settings = refs.flatMap { ref =>
       |        val current = extracted.get(ref / scalaVersion)
       |        val scala =
       |          if (binary(current) == "$scalaBinary") current
       |          else extracted.get(ref / crossScalaVersions).find(v => binary(v) == "$scalaBinary").getOrElse(current)
       |        Seq(
       |          ref / version := extracted.get(ref / version) + "$suffix",
       |          ref / scalaVersion := scala,
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

object MavenBuilder extends StagingBuilder:
  protected def publishTo(req: BuildRequest, staging: File): Unit =
    val cmd = Builders.launcher(req, BuildTool.Maven, Some("mvnw")) ++ Seq(
      "--batch-mode", "--no-transfer-progress",
      "-DskipTests", "-Dmaven.javadoc.skip=true", "-Dgpg.skip=true",
      s"-DaltDeploymentRepository=source-deps::default::${Builders.fileUri(staging)}",
      "deploy"
    )
    Builders.runLogged(req, cmd)

object GradleBuilder extends StagingBuilder:
  protected def publishTo(req: BuildRequest, staging: File): Unit =
    val init = new File(req.workDir, "source-deps.init.gradle")
    sbt.io.IO.write(init, initScript(Builders.fileUri(staging)))
    val cmd = Builders.launcher(req, BuildTool.Gradle, Some("gradlew")) ++ Seq(
      "--init-script", init.getAbsolutePath, "--console=plain",
      "publishAllPublicationsToSourceDepsStagingRepository"
    )
    Builders.runLogged(req, cmd)

  /** Adds maven-publish (and a publication, when the build declares none) to every JVM project. */
  private def initScript(stagingUri: String): String =
    s"""allprojects { p ->
       |  p.plugins.withId('java') {
       |    p.pluginManager.apply('maven-publish')
       |    p.afterEvaluate {
       |      if (!p.group) p.group = p.rootProject.name
       |      def publishing = p.extensions.getByType(PublishingExtension)
       |      if (publishing.publications.withType(MavenPublication).isEmpty()) {
       |        publishing.publications.create('sourceDeps', MavenPublication) { from p.components.java }
       |      }
       |      publishing.repositories.maven { name = 'sourceDepsStaging'; url = '$stagingUri' }
       |    }
       |  }
       |}
       |""".stripMargin

object MillBuilder extends StagingBuilder:
  protected def publishTo(req: BuildRequest, staging: File): Unit =
    val cmd = Builders.launcher(req, BuildTool.Mill, Some("mill")) ++ Seq(
      "--no-server", "--ticker", "false",
      "__.publishM2Local", "--m2RepoPath", staging.getAbsolutePath
    )
    Builders.runLogged(req, cmd)

object ScalaCliBuilder extends StagingBuilder:
  protected def publishTo(req: BuildRequest, staging: File): Unit =
    // since Scala 3.5 the `scala` runner is scala-cli itself
    val launcher = Builders.launcher(req, BuildTool.ScalaCli, None) match
      case Seq("scala-cli") if !Builders.onPath("scala-cli") && Builders.onPath("scala") => Seq("scala")
      case other                                                                       => other
    val cmd = launcher ++ Seq(
      "--power", "publish", ".", "--publish-repository", Builders.fileUri(staging)
    )
    Builders.runLogged(req, cmd)
