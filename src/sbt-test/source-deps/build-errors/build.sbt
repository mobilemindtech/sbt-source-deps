ThisBuild / scalaVersion := "3.8.4"

// the Gradle command does not exist
lazy val missingTool = project.settings(
  sourceDependencies += SourceDep.local((LocalRootProject / baseDirectory).value / "gradle-lib"),
  sourceDepsCommands := sourceDepsCommands.value.updated(BuildTool.Gradle, Seq("no-such-gradle"))
)

// the sbt build skips publishing
lazy val nothingPublished = project.settings(
  sourceDependencies += SourceDep.local((LocalRootProject / baseDirectory).value / "sbt-lib")
)

def failure(r: Result[?]): String = r match
  case Result.Inc(i)   => Incomplete.allExceptions(i).map(_.getMessage).mkString("\n")
  case Result.Value(_) => sys.error("expected a failure")

def expect(message: String, fragments: String*): Unit =
  fragments.filterNot(message.contains).foreach(f => sys.error(s"'$f' not found in:\n$message"))

val checkMissingTool = taskKey[Unit]("")
val checkNothingPublished = taskKey[Unit]("")

lazy val root = (project in file(".")).settings(
  checkMissingTool := Def.uncached {
    expect(failure((missingTool / sourceDepsResolve).result.value), "could not run `no-such-gradle`", "sourceDepsCommands")
  },
  checkNothingPublished := Def.uncached {
    expect(failure((nothingPublished / sourceDepsResolve).result.value), "did not publish any module", "publish / skip")
  }
)
