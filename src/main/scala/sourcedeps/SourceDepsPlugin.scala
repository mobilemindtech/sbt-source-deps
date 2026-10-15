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
    val sourceDepsResolve = taskKey[Seq[ModuleID]]("Builds stale source dependencies and returns their modules.")
    val sourceDepsUpdate = taskKey[Unit]("Fetches git source dependencies and rebuilds those that changed.")
    val sourceDepsStatus = taskKey[Unit]("Shows whether each source dependency is up to date.")
    val sourceDepsClean = taskKey[Unit]("Deletes clones and published artifacts of the source dependencies.")

  import autoImport.*

  private def engine = Def.setting {
    (log: Logger) => Engine(sourceDepsDirectory.value, sourceDepsCommands.value, log)
  }

  override def buildSettings: Seq[Setting[?]] = Seq(
    sourceDepsDirectory := (LocalRootProject / baseDirectory).value / ".source-deps",
    sourceDepsCommands := Builders.defaultCommands
  )

  override def projectSettings: Seq[Setting[?]] = Seq(
    sourceDependencies := Nil,
    resolvers ++= {
      if sourceDependencies.value.isEmpty then Nil
      else Seq(MavenRepository("source-deps", (sourceDepsDirectory.value / "repo").toURI.toString))
    },
    // uncached: the up-to-date check must run on every update, never be replayed from the action cache
    allDependencies := Def.uncached(allDependencies.value ++ sourceDepsResolve.value),
    sourceDepsResolve := Def.uncached {
      val e = engine.value(streams.value.log)
      sourceDependencies.value.flatMap(e.resolve(_)).map(c => ModuleID(c.groupId, c.artifactId, c.version))
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
