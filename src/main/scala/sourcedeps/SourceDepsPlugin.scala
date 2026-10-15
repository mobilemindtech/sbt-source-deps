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
    val sourceDepsCommands = settingKey[Map[BuildTool, Seq[String]]]("Command used to launch each build tool when the project has no wrapper.")
    val sourceDepsEnv = settingKey[Map[String, String]]("Extra environment variables for the dependency builds (e.g. JAVA_HOME).")
    val sourceDepsResolve = taskKey[Seq[ModuleID]]("Builds stale source dependencies and returns their modules.")
    val sourceDepsForcedVersions = taskKey[Seq[ModuleID]]("Every module published by the source dependencies; their versions win over any other.")
    val sourceDepsUpdate = taskKey[Unit]("Fetches git source dependencies and rebuilds those that changed.")
    val sourceDepsStatus = taskKey[Unit]("Shows whether each source dependency is up to date.")
    val sourceDepsClean = taskKey[Unit]("Deletes clones and published artifacts of the source dependencies.")

  import autoImport.*

  private val sourceDepsResolved = taskKey[Seq[Resolved]]("Resolution of each source dependency.").withRank(KeyRanks.Invisible)

  private def moduleId(c: Coordinates): ModuleID = ModuleID(c.groupId, c.artifactId, c.version)

  private def engine = Def.setting {
    (log: Logger) =>
      Engine(sourceDepsDirectory.value, sourceDepsCommands.value, sourceDepsEnv.value, scalaBinaryVersion.value, log)
  }

  override def buildSettings: Seq[Setting[?]] = Seq(
    // a build launched by this plugin (as a dependency) shares the working directory of the outer build
    sourceDepsDirectory := sys.props.get(Nested.DirProp).map(file)
      .getOrElse((LocalRootProject / baseDirectory).value / ".source-deps"),
    sourceDepsCommands := Builders.defaultCommands,
    sourceDepsEnv := Map.empty
  )

  override def projectSettings: Seq[Setting[?]] = Seq(
    sourceDependencies := Nil,
    resolvers ++= {
      if sourceDependencies.value.isEmpty then Nil
      else Seq(MavenRepository("source-deps", (sourceDepsDirectory.value / "repo").toURI.toString))
    },
    // uncached: the up-to-date check must run on every update, never be replayed from the action cache
    allDependencies := Def.uncached(allDependencies.value ++ sourceDepsResolve.value),
    // `1.0.0+src.x` ties with `1.0.0` and loses to `1.0.1`: force the source build over any transitive version
    csrConfiguration := Def.uncached {
      val forced = sourceDepsForcedVersions.value.map { m =>
        import lmcoursier.definitions.{Module, ModuleName, Organization}
        (Module(Organization(m.organization), ModuleName(m.name), Map.empty), m.revision)
      }
      val conf = csrConfiguration.value
      conf.withForceVersions(conf.forceVersions ++ forced)
    },
    sourceDepsResolve := Def.uncached(sourceDepsResolved.value.flatMap(_.selected).map(moduleId)),
    sourceDepsForcedVersions := Def.uncached(sourceDepsResolved.value.flatMap(_.published).distinct.map(moduleId)),
    sourceDepsResolved := Def.uncached {
      val e = engine.value(streams.value.log)
      val binary = scalaBinaryVersion.value
      sourceDependencies.value.map { dep =>
        val resolved = e.resolve(dep)
        Nested.record(dep, binary)
        resolved
      }
    },
    sourceDepsUpdate := Def.uncached {
      val e = engine.value(streams.value.log)
      sourceDependencies.value.foreach(e.resolve(_, fetch = true))
    },
    sourceDepsStatus := Def.uncached {
      val log = streams.value.log
      val e = engine.value(log)
      sourceDependencies.value.foreach { dep =>
        val status = e.isUpToDate(dep) match
          case Some(true)  => "up to date"
          case Some(false) => "stale (will rebuild)"
          case None        => "not checked out"
        log.info(s"${dep.id}: $dep -> $status")
      }
    },
    sourceDepsClean := Def.uncached {
      val e = engine.value(streams.value.log)
      sourceDependencies.value.foreach(e.clean)
    }
  )
