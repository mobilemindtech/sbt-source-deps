package sourcedeps

import java.io.File
import java.util.Properties
import sbt.*
import sbt.Keys.*
import sbt.librarymanagement.CrossVersion

/**
 * `_checkouts` mode, after rebar3: every sbt 2 build in `<build root>/_checkouts/` is loaded together
 * with the consumer's build, and a project declaring a module that a checkout project produces depends
 * on that project instead. Edits in the checkout compile incrementally, with no publishing.
 *
 * External builds are only loaded when a project of the root build references them, and their project
 * ids are unknown before loading. So:
 *   - while loading, [[SourceDepsPlugin.derivedProjects]] adds a hidden project depending on the root
 *     project of each checkout build, which makes sbt load them;
 *   - after loading, `Global / buildDependencies` gets the real edges (consumer -> checkout project),
 *     found by matching `libraryDependencies` with the checkout projects' `projectID`;
 *   - versions of the checkout modules are forced, or a higher published version requested transitively
 *     would win over the local module (and drop its dependencies);
 *   - `onLoad` aligns the checkout projects' Scala version with their consumers'.
 */
private[sourcedeps] object Checkouts:
  val DirProp = "sbt.source-deps.checkouts"
  val DirEnv = "SBT_SOURCE_DEPS_CHECKOUTS"
  val HiddenId = "sourceDepsCheckouts"

  /** A consumer project and the checkout project it depends on instead of a published module. */
  final case class Link(consumer: ProjectRef, checkout: ProjectRef)

  enum Entry:
    /** An sbt 2 build: loaded with the consumer's build. */
    case Build(dir: File)
    /** Another tool or sbt 1: built and published like `SourceDep.local`, its modules forced. */
    case Published(dir: File, tool: BuildTool)
    case Skipped(dir: File, reason: String)

  /** `<root>/_checkouts`, or the directory named by the property/variable (relative to the build root). */
  def directory(buildRoot: File): File =
    sys.props.get(DirProp).orElse(sys.env.get(DirEnv)).filter(_.trim.nonEmpty) match
      case Some(path) =>
        val f = new File(path)
        if f.isAbsolute then f else new File(buildRoot, path)
      case None => new File(buildRoot, "_checkouts")

  def entries(buildRoot: File): Seq[Entry] =
    Option(directory(buildRoot).listFiles()).getOrElse(Array.empty[File]).toSeq
      .filter(f => f.isDirectory && !f.getName.startsWith("."))
      .sortBy(_.getName)
      .map { dir =>
        Builders.detect(dir) match
          case Some(BuildTool.Sbt) if !sbtVersion(dir).exists(v => !v.startsWith("2.")) => Entry.Build(dir)
          case Some(tool)                                                                => Entry.Published(dir, tool)
          case None                                                                      => Entry.Skipped(dir, "no build detected")
      }

  def builds(buildRoot: File): Seq[File] = entries(buildRoot).collect { case Entry.Build(dir) => dir }

  /** Checkouts that sbt 2 cannot load, as source dependencies built with their own tool. */
  def published(buildRoot: File): Seq[SourceDep] =
    entries(buildRoot).collect { case Entry.Published(dir, tool) => SourceDep.local(dir).buildTool(tool) }

  private def sbtVersion(dir: File): Option[String] =
    val f = new File(dir, "project/build.properties")
    if !f.isFile then None
    else
      val p = Properties()
      sbt.io.Using.fileInputStream(f)(p.load)
      Option(p.getProperty("sbt.version")).map(_.trim)

  /**
   * The hidden project that makes sbt load the checkout builds (none without checkouts).
   *
   * `extraProjects` gets no hint of the build it is called for: a checkout that uses this plugin too
   * calls it for its own build, and the order of the calls changes on `reload`. The base is relative,
   * so it resolves inside whichever build is loading; there the hidden project only refers to builds
   * that are loaded anyway, and only the root build's one is used (see [[projects]]).
   */
  def extra: Seq[Project] =
    val root = new File(sys.props("user.dir")).getCanonicalFile
    val found = builds(root)
    if found.isEmpty then Nil
    else
      Seq(
        Project(HiddenId, file("target/source-deps-checkouts"))
          .dependsOn(found.map(b => ClasspathDependency(RootProject(b), None))*)
          .settings(
            publish / skip := true,
            allowMismatchScala := true,
            sourceDepsCheckoutsHidden := true
          )
      )

  private[sourcedeps] val sourceDepsCheckoutsHidden =
    settingKey[Boolean]("Marks the project that loads the checkout builds.").withRank(KeyRanks.Invisible)

  /** Projects of the root build, and projects of the checkout builds (those the hidden project loads). */
  val projects: Def.Initialize[(Seq[ProjectRef], Seq[ProjectRef])] = Def.setting {
    val structure = loadedBuild.value
    val rootUnit = structure.units(structure.root)
    val own = rootUnit.defined.keys.toSeq.filter(_ != HiddenId).sorted.map(ProjectRef(structure.root, _))
    val checkoutBuilds = rootUnit.defined.get(HiddenId).toSeq.flatMap(_.dependencies.map(_.project.build)).toSet
    val external = structure.units.toSeq
      .filter((uri, _) => checkoutBuilds(uri))
      .flatMap((uri, unit) => unit.defined.keys.toSeq.filter(_ != HiddenId).sorted.map(ProjectRef(uri, _)))
    (own, external)
  }

  /** (organization, name) of a module, without the Scala suffix. */
  private def key(m: ModuleID): (String, String) = (m.organization, m.name)

  val links: Def.Initialize[Seq[Link]] = Def.settingDyn {
    val (own, external) = projects.value
    val provided = external.map(r => (r / projectID).apply(m => r -> key(m))).join
    val wanted = own.map(r => (r / libraryDependencies).apply(deps => r -> deps.map(key).toSet)).join
    Def.setting {
      val byModule = provided.value
      for
        (consumer, keys) <- wanted.value
        (checkout, k) <- byModule
        if keys(k)
      yield Link(consumer, checkout)
    }
  }

  /** The root build's projects get classpath edges to the checkout projects they declare. */
  val buildDependencies: Def.Initialize[sbt.internal.BuildDependencies] = Def.setting {
    val bd = (Global / Keys.buildDependencies).value
    links.value.groupBy(_.consumer).foldLeft(bd) { case (acc, (consumer, ls)) =>
      val existing = acc.classpathRefs(consumer).toSet
      val added = ls.map(_.checkout).filterNot(existing).distinct
      acc.addClasspath(consumer, added.map(r => ClasspathDep.ResolvedClasspathDependency(r, None): ClasspathDep[ProjectRef])*)
    }
  }

  /**
   * Modules of every project of the checkout builds a consumer uses, at the checkout's version and with
   * the checkout's Scala suffix (a 2.13 project used from Scala 3 is `_2.13`).
   */
  val forcedModules: Def.Initialize[Seq[ModuleID]] = Def.settingDyn {
    val me = thisProjectRef.value
    val (_, external) = projects.value
    val usedBuilds = links.value.filter(_.consumer == me).map(_.checkout.build).toSet
    external.filter(r => usedBuilds(r.build)).map { r =>
      Def.setting {
        val m = (r / projectID).value
        val name = CrossVersion((r / projectID).value.crossVersion, (r / scalaVersion).value, (r / scalaBinaryVersion).value)
          .fold(m.name)(_(m.name))
        ModuleID(m.organization, name, m.revision)
      }
    }.join
  }

  /** A consumer on Scala 3 may use a checkout project that only builds for 2.13, as with published jars. */
  val mismatchAllowed: Def.Initialize[Boolean] = Def.settingDyn {
    val me = thisProjectRef.value
    val mine = scalaBinaryVersion.value
    val targets = links.value.filter(_.consumer == me).map(_.checkout)
    targets.map(r => r / scalaBinaryVersion).join.apply(_.exists(b => b != mine && b == "2.13" && mine == "3"))
  }

  private val aligned = AttributeKey[Boolean]("sourceDepsCheckoutsAligned")
  private val reported = AttributeKey[Boolean]("sourceDepsCheckoutsReported")

  /** `reload` keeps the state's attributes: the next load must align and report again. */
  def onUnload(state: State): State = state.remove(aligned).remove(reported)

  /**
   * After loading: checkout projects compile with their consumers' Scala version when their
   * `crossScalaVersions` has one with the same binary version. Appended settings win over the checkout
   * build's own. Appending unloads and loads again, so this runs twice: align on the first pass,
   * report once the settings are final.
   */
  def onLoad(state: State): State =
    if state.get(aligned).contains(true) then state
    else
      val extracted = Project.extract(state)
      val ls = extracted.get(Global / sourceDepsCheckoutLinks)
      val (_, external) = extracted.get(Global / sourceDepsCheckoutProjects)
      val alignment = scalaAlignment(extracted, ls, external)
      val marked = state.put(aligned, true)
      val next = if alignment.isEmpty then marked else extracted.appendWithoutSession(alignment, marked)
      if next.get(reported).contains(true) then next
      else
        report(Project.extract(next), next.log)
        next.put(reported, true)

  private def report(extracted: Extracted, log: Logger): Unit =
    val root = extracted.get(LocalRootProject / baseDirectory)
    entries(root).foreach {
      case Entry.Skipped(dir, reason) => log.warn(s"[source-deps] _checkouts: ignoring ${dir.getName}: $reason")
      case Entry.Published(dir, tool) =>
        val how = if tool == BuildTool.Sbt then "an sbt 1 build" else s"a ${tool.toString.toLowerCase} build"
        log.info(s"[source-deps] _checkouts: ${dir.getName} is $how: built with its own tool, its modules replace the published ones")
      case Entry.Build(_) => ()
    }
    val ls = extracted.get(Global / sourceDepsCheckoutLinks)
    val (_, external) = extracted.get(Global / sourceDepsCheckoutProjects)
    ls.groupBy(_.checkout).toSeq.sortBy(_._1.project).foreach { (checkout, l) =>
      val consumers = l.map(_.consumer).distinct
      val binaries = consumers.map(c => extracted.get(c / scalaBinaryVersion)).distinct
      val version = extracted.get(checkout / scalaVersion)
      log.info(s"[source-deps] _checkouts: ${checkout.project} (${new File(checkout.build).getName}, Scala $version) replaces its published module in ${consumers.map(_.project).mkString(", ")}")
      if binaries.size > 1 then
        log.warn(s"[source-deps] _checkouts: ${checkout.project} is used from Scala ${binaries.mkString(" and ")}; it compiles with one version only")
    }
    val usedBuilds = ls.map(_.checkout.build).toSet
    external.map(_.build).distinct.filterNot(usedBuilds).foreach { b =>
      log.warn(s"[source-deps] _checkouts: no project declares a module of ${new File(b).getName}; add it to libraryDependencies to use the checkout")
    }

  /**
   * `scalaVersion` for the checkout projects whose consumers all use one Scala binary version, when
   * their `crossScalaVersions` has it. Projects of a used checkout build without consumers of their own
   * (`util` under `core`) follow the same version.
   */
  private def scalaAlignment(extracted: Extracted, ls: Seq[Link], external: Seq[ProjectRef]): Seq[Setting[?]] =
    def align(r: ProjectRef, binary: String): Seq[Setting[?]] =
      val current = extracted.get(r / scalaVersion)
      if CrossVersion.binaryScalaVersion(current) == binary then Nil
      else extracted.get(r / crossScalaVersions).find(v => CrossVersion.binaryScalaVersion(v) == binary).toSeq.map(v => r / scalaVersion := v)
    def binariesOf(links: Seq[Link]) = links.map(l => extracted.get(l.consumer / scalaBinaryVersion)).distinct
    val linked = ls.groupBy(_.checkout).toSeq.flatMap { (checkout, l) =>
      binariesOf(l) match
        case Seq(binary) => align(checkout, binary)
        case _           => Nil
    }
    val others = ls.map(_.checkout.build).distinct.flatMap { b =>
      binariesOf(ls.filter(_.checkout.build == b)) match
        case Seq(binary) => external.filter(r => r.build == b && !ls.exists(_.checkout == r)).flatMap(align(_, binary))
        case _           => Nil
    }
    linked ++ others

  private[sourcedeps] val sourceDepsCheckoutProjects =
    settingKey[(Seq[ProjectRef], Seq[ProjectRef])]("Projects of the root build and of the checkout builds.").withRank(KeyRanks.Invisible)

  private[sourcedeps] val sourceDepsCheckoutLinks =
    settingKey[Seq[Link]]("Consumer -> checkout project links of the _checkouts mode.").withRank(KeyRanks.Invisible)
