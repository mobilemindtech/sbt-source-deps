scalaVersion := "3.8.4"
sourceDependencies += SourceDep.local(file("middle"))
// greeter comes transitively from middle's own source dependency, and must win over this version
libraryDependencies += "example" %% "greeter" % "9.9.9"

// the nested sbt does not receive plugin.version: write middle's plugins.sbt with it
val writeMiddlePlugin = taskKey[Unit]("adds sbt-source-deps to the middle build")
writeMiddlePlugin := IO.write(
  baseDirectory.value / "middle/project/plugins.sbt",
  s"""addSbtPlugin("br.com.mobilemind" % "sbt-source-deps" % "${sys.props("plugin.version")}")\n"""
)
