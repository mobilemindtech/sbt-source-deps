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

  /**
   * A consumer project and the checkout project it uses instead of the `declared` module: on its
   * classpath, or as a compiler plugin (`-Xplugin:` with its jar). `scala` is the version the checkout
   * project must compile with, when it has to change.
   */
  final case class Link(
      consumer: ProjectRef,
      checkout: ProjectRef,
      declared: ModuleID,
      compilerPlugin: Boolean,
      scala: Option[String]
  )

  /** What the selection needs to know about a checkout project. */
  private[sourcedeps] final case class Candidate(ref: ProjectRef, scalaVersion: String, crossScalaVersions: Seq[String]):
    def versions: Seq[String] = (crossScalaVersions :+ scalaVersion).distinct

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

  private def isCompilerPlugin(m: ModuleID): Boolean = m.configurations.exists(_.startsWith("plugin"))

  private def binary(v: String): String = CrossVersion.binaryScalaVersion(v)

  /** Numeric comparison of Scala versions; a pre-release (`3.10.0-RC2`) sorts before its release. */
  private[sourcedeps] def compareVersions(a: String, b: String): Int =
    def parts(v: String) = v.takeWhile(_ != '-').split('.').toSeq.map(_.toIntOption.getOrElse(0))
    val (pa, pb) = (parts(a), parts(b))
    val numeric = pa.zipAll(pb, 0, 0).map(_ compare _).find(_ != 0).getOrElse(0)
    if numeric != 0 then numeric else (b.contains('-') compare a.contains('-'))

  /**
   * The checkout project (and Scala version) to use for `dep`, among the projects producing the same
   * module: builds with one project per Scala version (`nir2_12`, `nir2_13`, `nir3`) have several.
   *   - `CrossVersion.full` (compiler plugins): a project that builds for the consumer's exact version;
   *   - binary cross versions: one that builds for the consumer's binary version, at the consumer's
   *     version when it can, else the highest version not above it (Scala 3 reads TASTy only from
   *     versions up to its own); failing that, a 2.13 project for a Scala 3 consumer, unchanged;
   *   - no cross version: the only candidate, or the one matching the consumer's binary version.
   */
  private[sourcedeps] def select(dep: ModuleID, consumerScala: String, candidates: Seq[Candidate]): Option[(Candidate, Option[String])] =
    val consumerBinary = binary(consumerScala)
    def target(c: Candidate, wanted: String): Option[String] =
      val sameBinary = c.versions.filter(v => binary(v) == wanted)
      val chosen =
        if sameBinary.contains(consumerScala) then Some(consumerScala)
        else
          val notAbove = sameBinary.filter(v => compareVersions(v, consumerScala) <= 0)
          (if notAbove.nonEmpty then notAbove else sameBinary).sortWith(compareVersions(_, _) > 0).headOption
      chosen
    def change(c: Candidate, v: String) = if v == c.scalaVersion then None else Some(v)
    def byBinary(wanted: String) =
      candidates.flatMap(c => target(c, wanted).map(v => (c, change(c, v))))
        .sortBy((c, ch) => (ch.isDefined, c.ref.project)).headOption
    dep.crossVersion match
      case _: sbt.librarymanagement.Full =>
        candidates.find(_.versions.contains(consumerScala)).map(c => (c, change(c, consumerScala)))
      case _: sbt.librarymanagement.Disabled if candidates.size == 1 =>
        Some((candidates.head, None))
      case cross =>
        val wanted = cross match
          case _: sbt.librarymanagement.For3Use2_13 if consumerBinary == "3"    => "2.13"
          case _: sbt.librarymanagement.For2_13Use3 if consumerBinary == "2.13" => "3"
          case _                                                                => consumerBinary
        byBinary(wanted).orElse {
          if wanted == "3" then candidates.find(_.versions.exists(v => binary(v) == "2.13")).map(c => (c, None))
          else None
        }

  val links: Def.Initialize[Seq[Link]] = Def.settingDyn {
    val (own, external) = projects.value
    val provided = external.map { r =>
      Def.setting(key((r / projectID).value) -> Candidate(r, (r / scalaVersion).value, (r / crossScalaVersions).value))
    }.join
    val wanted = own.map(r => Def.setting((r, (r / scalaVersion).value, (r / libraryDependencies).value))).join
    Def.setting {
      val byModule = provided.value.groupBy(_._1).map((k, cs) => k -> cs.map(_._2))
      for
        (consumer, consumerScala, deps) <- wanted.value
        dep <- deps
        candidates <- byModule.get(key(dep)).toSeq
        (chosen, scala) <- select(dep, consumerScala, candidates).toSeq
      yield Link(consumer, chosen.ref, dep, isCompilerPlugin(dep), scala)
    }
  }

  /** The root build's projects get classpath edges to the checkout projects they use as libraries. */
  val buildDependencies: Def.Initialize[sbt.internal.BuildDependencies] = Def.setting {
    val bd = (Global / Keys.buildDependencies).value
    links.value.filterNot(_.compilerPlugin).groupBy(_.consumer).foldLeft(bd) { case (acc, (consumer, ls)) =>
      val existing = acc.classpathRefs(consumer).toSet
      val added = ls.filterNot(l => existing(l.checkout)).groupBy(_.checkout).toSeq.sortBy(_._1.project).map { (r, l) =>
        // `% Test` stays a test dependency; a compile one (no configuration) wins over it
        val configs = l.map(_.declared.configurations).distinct
        val mapping = if configs.contains(None) then None else configs.flatten.headOption.filter(_.matches("\\w+")).map(c => s"$c->compile")
        ClasspathDep.ResolvedClasspathDependency(r, mapping): ClasspathDep[ProjectRef]
      }
      acc.addClasspath(consumer, added*)
    }
  }

  /**
   * Compiler plugins from checkouts: passed to scalac as jars, never resolved. Each jar is copied under
   * a name holding its hash: zinc compares `scalacOptions`, not the plugin's content, so an edited plugin
   * changes the option and recompiles the consumer.
   */
  val compilerPluginOptions: Def.Initialize[Task[Seq[String]]] = Def.taskDyn {
    val me = thisProjectRef.value
    val plugins = links.value.filter(l => l.consumer == me && l.compilerPlugin).map(_.checkout).distinct
    val converter = fileConverter.value
    val dir = target.value / "source-deps-plugins"
    plugins.map(r => r / Compile / packageBin).join.map(_.map { f =>
      val jar = converter.toPath(f).toFile
      val hash = Hashing.hex(java.security.MessageDigest.getInstance("SHA-256").digest(sbt.io.IO.readBytes(jar))).take(16)
      val copy = dir / hash / jar.getName
      if !copy.isFile then sbt.io.IO.copyFile(jar, copy)
      s"-Xplugin:${copy.getAbsolutePath}"
    })
  }

  /**
   * Coursier resolves the modules of other projects from `csrInterProjectDependencies` (the projects
   * reached through `dependsOn`, with their platform) and falls back to Ivy descriptors for the others,
   * named without the platform: `nativelib_3` where Scala Native asks for `nativelib_native0.5_3`. The
   * checkout projects are linked after loading, so they would only get the fallback; their coursier
   * projects are added here instead.
   */
  val interProjects: Def.Initialize[Task[Seq[lmcoursier.definitions.Project]]] =
    Def
      .task {
        val me = thisProjectRef.value
        val refs =
          if !links.value.exists(_.consumer == me) then Nil
          else (Global / Keys.buildDependencies).value.classpathTransitiveRefs(me).filter(_.build != me.build).distinct
        ScopeFilter(inProjects(refs*))
      }
      .flatMapTask { case filter => Def.task(csrProject.all(filter).value) }

  /**
   * The declared modules a checkout replaces, removed before resolution: libraries come through the
   * project dependency, compiler plugins as jars.
   */
  val replacedModules: Def.Initialize[Seq[ModuleID]] = Def.setting {
    val me = thisProjectRef.value
    links.value.filter(_.consumer == me).map(_.declared).distinct
  }

  /**
   * Only the declarations themselves go: `allDependencies` also holds the checkout project's own module
   * (from `projectDependencies`), with the same organization and name, which brings its dependencies.
   */
  def withoutReplaced(deps: Seq[ModuleID], replaced: Seq[ModuleID]): Seq[ModuleID] = deps.diff(replaced)

  /**
   * A declared module whose artifact name differs from the checkout project's (Scala Native adds its
   * platform, `nativelib_native0.5_3`, outside `projectID`) is not recognized as that project by
   * resolution: it is excluded, so another library cannot bring the published one back.
   */
  val exclusions: Def.Initialize[Seq[ExclusionRule]] = Def.settingDyn {
    val me = thisProjectRef.value
    val sv = scalaVersion.value
    val sbv = scalaBinaryVersion.value
    val mine = links.value.filter(l => l.consumer == me && !l.compilerPlugin)
    mine.map { l =>
      Def.setting {
        val m = (l.checkout / projectID).value
        val own = CrossVersion(m.crossVersion, (l.checkout / scalaVersion).value, (l.checkout / scalaBinaryVersion).value)
          .fold(m.name)(_(m.name))
        val declared = CrossVersion(l.declared.crossVersion, sv, sbv).fold(l.declared.name)(_(l.declared.name))
        if declared == own then None else Some(ExclusionRule(l.declared.organization, declared))
      }
    }.join.apply(_.flatten.distinct)
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
        val name = CrossVersion(m.crossVersion, (r / scalaVersion).value, (r / scalaBinaryVersion).value)
          .fold(m.name)(_(m.name))
        ModuleID(m.organization, name, m.revision)
      }
    }.join
  }

  /** A consumer on Scala 3 may use a checkout project that only builds for 2.13, as with published jars. */
  val mismatchAllowed: Def.Initialize[Boolean] = Def.settingDyn {
    val me = thisProjectRef.value
    val mine = scalaBinaryVersion.value
    val targets = links.value.filter(l => l.consumer == me && !l.compilerPlugin).map(_.checkout)
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
      val version = extracted.get(checkout / scalaVersion)
      val wanted = l.map(_.scala.getOrElse(version)).distinct
      val as = if l.forall(_.compilerPlugin) then "compiler plugin" else "module"
      log.info(s"[source-deps] _checkouts: ${checkout.project} (${new File(checkout.build).getName}, Scala $version) replaces its published $as in ${consumers.map(_.project).mkString(", ")}")
      if wanted.size > 1 then
        log.warn(s"[source-deps] _checkouts: ${checkout.project} is wanted with Scala ${wanted.mkString(" and ")}; it compiles with one version only")
    }
    val usedBuilds = ls.map(_.checkout.build).toSet
    external.map(_.build).distinct.filterNot(usedBuilds).foreach { b =>
      log.warn(s"[source-deps] _checkouts: no project declares a module of ${new File(b).getName}; add it to libraryDependencies to use the checkout")
    }

  /**
   * `scalaVersion` for the checkout projects whose links all ask for one version (see [[select]]). The
   * other projects of a used checkout build (`util` under `core`, `nir` under `tools`) follow that
   * version when their `crossScalaVersions` has it.
   */
  private def scalaAlignment(extracted: Extracted, ls: Seq[Link], external: Seq[ProjectRef]): Seq[Setting[?]] =
    def current(r: ProjectRef) = extracted.get(r / scalaVersion)
    val targets: Map[ProjectRef, Seq[String]] =
      ls.groupBy(_.checkout).map((r, l) => r -> l.map(_.scala.getOrElse(current(r))).distinct)
    val linked = targets.toSeq.collect { case (r, Seq(v)) if v != current(r) => r / scalaVersion := v }
    val others = ls.map(_.checkout.build).distinct.flatMap { b =>
      targets.filter(_._1.build == b).values.flatten.toSeq.distinct match
        case Seq(v) =>
          external.filter(r => r.build == b && !targets.contains(r)).flatMap { r =>
            val versions = extracted.get(r / crossScalaVersions) :+ current(r)
            if current(r) != v && versions.contains(v) then Seq(r / scalaVersion := v) else Nil
          }
        case _ => Nil
    }
    linked ++ others

  private[sourcedeps] val sourceDepsCheckoutProjects =
    settingKey[(Seq[ProjectRef], Seq[ProjectRef])]("Projects of the root build and of the checkout builds.").withRank(KeyRanks.Invisible)

  private[sourcedeps] val sourceDepsCheckoutLinks =
    settingKey[Seq[Link]]("Consumer -> checkout project links of the _checkouts mode.").withRank(KeyRanks.Invisible)
