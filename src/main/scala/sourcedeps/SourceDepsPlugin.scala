package sourcedeps

import sbt.*
import sbt.Keys.*

object SourceDepsPlugin extends AutoPlugin:
  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  object autoImport:
    type SourceDep = sourcedeps.SourceDep
    val SourceDep = sourcedeps.SourceDep
    type BuildTool = sourcedeps.BuildTool
    val BuildTool = sourcedeps.BuildTool

    val sourceDependencies = settingKey[Seq[SourceDep]]("Dependencies built from git repositories or local directories.")
    val sourceDepsDirectory = settingKey[File]("Where source dependencies are cloned and published.")
    val sourceDepsShared = settingKey[Boolean]("Use the cache shared by every project of the machine (sourceDepsSharedDirectory) instead of <build root>/.source-deps.")
    val sourceDepsSharedDirectory = settingKey[File]("Location of the shared cache.")
    val sourceDepsCommands = settingKey[Map[BuildTool, Seq[String]]]("Command used to launch each build tool when the project has no wrapper.")
    val sourceDepsEnv = settingKey[Map[String, String]]("Extra environment variables for the dependency builds (e.g. JAVA_HOME).")
    val sourceDepsShowOutput = settingKey[Boolean]("Log the output of the dependency builds at info level instead of debug.")
    val sourceDepsResolve = taskKey[Seq[ModuleID]]("Builds stale source dependencies and returns their modules.")
    val sourceDepsForcedVersions = taskKey[Seq[ModuleID]]("Every module published by the source dependencies; their versions win over any other.")
    val sourceDepsUpdate = taskKey[Unit]("Fetches git source dependencies and rebuilds those that changed.")
    val sourceDepsStatus = taskKey[Unit]("Shows whether each source dependency is up to date.")
    val sourceDepsClean = taskKey[Unit]("Deletes clones and published artifacts of the source dependencies.")

  import autoImport.*

  private val sourceDepsResolved = taskKey[Seq[Resolved]]("Resolution of each source dependency.").withRank(KeyRanks.Invisible)
  private val sourceDepsCheckoutDeps =
    settingKey[Seq[SourceDep]]("Entries of _checkouts that sbt 2 cannot load, built like local source dependencies.").withRank(KeyRanks.Invisible)
  private val sourceDepsCheckoutResolved =
    taskKey[Seq[Resolved]]("Resolution of the _checkouts entries built with their own tool.").withRank(KeyRanks.Invisible)

  /** Source dependencies and published checkouts (the hidden project of the _checkouts mode has none). */
  private def allSourceDeps = Def.setting {
    val checkouts = if Checkouts.sourceDepsCheckoutsHidden.?.value.contains(true) then Nil else sourceDepsCheckoutDeps.value
    sourceDependencies.value ++ checkouts
  }

  private def moduleId(c: Coordinates): ModuleID = ModuleID(c.groupId, c.artifactId, c.version)

  private def engine = Def.setting {
    (log: Logger) =>
      Engine(
        sourceDepsDirectory.value, sourceDepsCommands.value, sourceDepsEnv.value, scalaBinaryVersion.value, log,
        sourceDepsShowOutput.value
      )
  }

  override def extraProjects: Seq[Project] = Checkouts.extra

  override def globalSettings: Seq[Setting[?]] = Seq(
    Checkouts.sourceDepsCheckoutProjects := Checkouts.projects.value,
    Checkouts.sourceDepsCheckoutLinks := Checkouts.links.value,
    buildDependencies := Checkouts.buildDependencies.value,
    onLoad := onLoad.value.andThen(Checkouts.onLoad),
    onUnload := onUnload.value.andThen(Checkouts.onUnload)
  )

  override def buildSettings: Seq[Setting[?]] = Seq(
    sourceDepsShared := SharedCache.enabledByDefault,
    sourceDepsSharedDirectory := SharedCache.defaultDirectory,
    // a build launched by this plugin (as a dependency) shares the working directory of the outer build
    sourceDepsDirectory := sys.props.get(Nested.DirProp).map(file).getOrElse {
      if sourceDepsShared.value then sourceDepsSharedDirectory.value
      else (LocalRootProject / baseDirectory).value / ".source-deps"
    },
    sourceDepsCommands := Builders.defaultCommands,
    sourceDepsEnv := Map.empty,
    sourceDepsShowOutput := false,
    sourceDepsCheckoutDeps := Checkouts.published((LocalRootProject / baseDirectory).value)
  )

  override def projectSettings: Seq[Setting[?]] = Seq(
    sourceDependencies := Nil,
    allowMismatchScala := allowMismatchScala.value || Checkouts.mismatchAllowed.value,
    resolvers ++= {
      if allSourceDeps.value.isEmpty then Nil
      else Seq(MavenRepository("source-deps", (sourceDepsDirectory.value / "repo").toURI.toString))
    },
    // uncached: the up-to-date check must run on every update, never be replayed from the action cache
    allDependencies := Def.uncached(allDependencies.value ++ sourceDepsResolve.value),
    // `1.0.0+src.x` ties with `1.0.0` and loses to `1.0.1`: force the source build over any transitive version
    // _checkouts: the local module wins the same way, over versions declared or requested transitively
    csrConfiguration := Def.uncached {
      val forced = (sourceDepsForcedVersions.value ++ Checkouts.forcedModules.value).map { m =>
        import lmcoursier.definitions.{Module, ModuleName, Organization}
        (Module(Organization(m.organization), ModuleName(m.name), Map.empty), m.revision)
      }
      val conf = csrConfiguration.value
      conf.withForceVersions(conf.forceVersions ++ forced)
    },
    sourceDepsResolve := Def.uncached(sourceDepsResolved.value.flatMap(_.selected).map(moduleId)),
    // published checkouts are not added as dependencies: forcing their modules replaces the declared ones
    sourceDepsForcedVersions := Def.uncached(
      (sourceDepsResolved.value ++ sourceDepsCheckoutResolved.value).flatMap(_.published).distinct.map(moduleId)
    ),
    sourceDepsCheckoutResolved := Def.uncached {
      val e = engine.value(streams.value.log)
      val binary = scalaBinaryVersion.value
      val depsEnv = sourceDepsEnv.value
      val checkouts = allSourceDeps.value.drop(sourceDependencies.value.size)
      checkouts.map { dep =>
        val resolved = e.resolve(dep)
        Nested.record(dep, binary, depsEnv)
        resolved
      }
    },
    sourceDepsResolved := Def.uncached {
      val e = engine.value(streams.value.log)
      val binary = scalaBinaryVersion.value
      val depsEnv = sourceDepsEnv.value
      sourceDependencies.value.map { dep =>
        val resolved = e.resolve(dep)
        Nested.record(dep, binary, depsEnv)
        resolved
      }
    },
    sourceDepsUpdate := Def.uncached {
      val e = engine.value(streams.value.log)
      allSourceDeps.value.foreach(e.resolve(_, fetch = true))
    },
    sourceDepsStatus := Def.uncached {
      val log = streams.value.log
      val e = engine.value(log)
      allSourceDeps.value.foreach { dep =>
        val status = e.isUpToDate(dep) match
          case Some(true)  => "up to date"
          case Some(false) => "stale (will rebuild)"
          case None        => "not checked out"
        log.info(s"${dep.id}: $dep -> $status")
      }
    },
    sourceDepsClean := Def.uncached {
      val e = engine.value(streams.value.log)
      allSourceDeps.value.foreach(e.clean)
    }
  )
