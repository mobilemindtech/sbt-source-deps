package sourcedeps

import java.io.File
import sbt.util.Logger

/** Where an sbt dependency that uses this plugin itself puts its own source dependencies (see [[Nested]]). */
final case class NestedContext(root: File, recordDir: File, chain: Seq[String])

/** Everything a builder needs to publish a project into a staging Maven repository. */
final case class BuildRequest(
    projectDir: File,
    staging: File,
    workDir: File,
    scalaBinaryVersion: String,
    commands: Map[BuildTool, Seq[String]],
    env: Map[String, String],
    log: Logger,
    label: String,
    nested: NestedContext
)

/**
 * Builders publish every module with the project's own version into `req.staging`, an empty Maven
 * repository; the engine then republishes them with the version suffix (see [[Republish]]).
 */
trait Builder:
  def publish(req: BuildRequest): Unit

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

  private[sourcedeps] def runLogged(req: BuildRequest, tool: BuildTool, cmd: Seq[String]): Unit =
    req.log.info(s"[source-deps] ${req.label}: ${cmd.mkString(" ")}")
    val r = Proc.run(cmd, req.projectDir, processEnv(req.env).toSeq)(line => req.log.debug(s"[${req.label}] $line"))
    r.notStarted.foreach { reason =>
      val name = tool.toString
      throw ProcessFailed(
        s"could not run `${cmd.head}` to build ${req.label} ($reason).\n" +
          s"Install it on the PATH, add the project's wrapper script, or point the plugin at it:\n" +
          s"  sourceDepsCommands := sourceDepsCommands.value.updated(BuildTool.$name, Seq(\"/path/to/${defaultCommands(tool).head}\"))"
      )
    }
    if !r.ok then
      r.out.takeRight(80).foreach(l => req.log.error(s"[${req.label}] $l"))
      throw ProcessFailed(s"build of ${req.label} failed (exit ${r.exitCode}) running: ${cmd.mkString(" ")}")

  /**
   * Most launch scripts (sbt's among them) run the `java` found on the PATH and ignore JAVA_HOME: when
   * JAVA_HOME is set for the builds, its `bin` directory goes first on the PATH as well.
   */
  private[sourcedeps] def processEnv(env: Map[String, String]): Map[String, String] =
    env.get("JAVA_HOME") match
      case Some(home) if !env.contains("PATH") =>
        val path = sys.env.get("PATH").fold("")(File.pathSeparator + _)
        env.updated("PATH", new File(home, "bin").getAbsolutePath + path)
      case _ => env

  private[sourcedeps] def fileUri(dir: File): String = dir.toURI.toString.replaceFirst("^file:/(?!/)", "file:///")

object SbtBuilder extends Builder:
  def publish(req: BuildRequest): Unit =
    val globalSettings = prepareGlobalSettings(req)
    val cmd = Builders.launcher(req, BuildTool.Sbt, None) ++ Seq(
      "--server", "--batch", "--no-colors", "--supershell=never"
    ) ++ req.env.get("JAVA_HOME").toSeq.flatMap(home => Seq("--java-home", home)) ++ Seq(
      s"-Dsbt.global.settings=${globalSettings.getAbsolutePath}",
      // read by this plugin when the dependency uses it too; ignored otherwise
      s"-D${Nested.DirProp}=${req.nested.root.getAbsolutePath}",
      s"-D${Nested.RecordProp}=${req.nested.recordDir.getAbsolutePath}",
      s"-D${Nested.ChainProp}=${req.nested.chain.mkString(",")}",
      "publish"
    )
    Builders.runLogged(req, BuildTool.Sbt, cmd)

  /**
   * The overrides are appended after load through a global settings file: `set every ...` cannot pick
   * a different Scala version per project, and the dependency's directory is never modified. The user's
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
      overrides(Builders.fileUri(req.staging), req.scalaBinaryVersion)
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
   * itself (`(ref / scalaVersion).value`) creates cycles there. Besides the staging repository, each
   * project switches to the entry of its `crossScalaVersions` matching the consumer's Scala binary
   * version, when there is one.
   */
  private def overrides(repoUri: String, scalaBinary: String): String =
    s"""val sourceDepsStagingApplied = settingKey[Boolean]("set by sbt-source-deps once its overrides are applied")
       |
       |Global / onLoad := {
       |  val previous = (Global / onLoad).value
       |  previous.andThen { state =>
       |    val extracted = Project.extract(state)
       |    val refs = extracted.structure.allProjectRefs
       |    // appendWithSession reloads the session, which runs onLoad again: stop once applied
       |    if (refs.forall(ref => extracted.getOpt(ref / sourceDepsStagingApplied).contains(true))) state
       |    else {
       |      def binary(v: String) = sbt.librarymanagement.CrossVersion.binaryScalaVersion(v)
       |      val settings = refs.flatMap { ref =>
       |        val current = extracted.get(ref / scalaVersion)
       |        val scala =
       |          if (binary(current) == "$scalaBinary") current
       |          else extracted.get(ref / crossScalaVersions).find(v => binary(v) == "$scalaBinary").getOrElse(current)
       |        Seq(
       |          ref / scalaVersion := scala,
       |          ref / sourceDepsStagingApplied := true,
       |          ref / publishTo := Some("source-deps-staging" at "$repoUri"),
       |          ref / publishMavenStyle := true,
       |          ref / Compile / packageDoc / publishArtifact := false
       |        )
       |      }
       |      extracted.appendWithSession(settings, state)
       |    }
       |  }
       |}
       |""".stripMargin

object MavenBuilder extends Builder:
  def publish(req: BuildRequest): Unit =
    val cmd = Builders.launcher(req, BuildTool.Maven, Some("mvnw")) ++ Seq(
      "--batch-mode", "--no-transfer-progress",
      "-DskipTests", "-Dmaven.javadoc.skip=true", "-Dgpg.skip=true",
      s"-DaltDeploymentRepository=source-deps::default::${Builders.fileUri(req.staging)}",
      "deploy"
    )
    Builders.runLogged(req, BuildTool.Maven, cmd)

object GradleBuilder extends Builder:
  def publish(req: BuildRequest): Unit =
    val init = new File(req.workDir, "source-deps.init.gradle")
    sbt.io.IO.write(init, initScript(Builders.fileUri(req.staging)))
    val cmd = Builders.launcher(req, BuildTool.Gradle, Some("gradlew")) ++ Seq(
      "--init-script", init.getAbsolutePath, "--console=plain",
      "publishAllPublicationsToSourceDepsStagingRepository"
    )
    Builders.runLogged(req, BuildTool.Gradle, cmd)

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

object MillBuilder extends Builder:
  def publish(req: BuildRequest): Unit =
    val cmd = Builders.launcher(req, BuildTool.Mill, Some("mill")) ++ Seq(
      "--no-server", "--ticker", "false",
      "__.publishM2Local", "--m2RepoPath", req.staging.getAbsolutePath
    )
    Builders.runLogged(req, BuildTool.Mill, cmd)

object ScalaCliBuilder extends Builder:
  def publish(req: BuildRequest): Unit =
    // since Scala 3.5 the `scala` runner is scala-cli itself
    val launcher = Builders.launcher(req, BuildTool.ScalaCli, None) match
      case Seq("scala-cli") if !Builders.onPath("scala-cli") && Builders.onPath("scala") => Seq("scala")
      case other                                                                       => other
    val cmd = launcher ++ Seq(
      "--power", "publish", ".", "--publish-repository", Builders.fileUri(req.staging)
    )
    Builders.runLogged(req, BuildTool.ScalaCli, cmd)
