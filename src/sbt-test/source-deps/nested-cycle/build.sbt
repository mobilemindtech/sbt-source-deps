scalaVersion := "3.8.4"
sourceDependencies += SourceDep.local(file("a"))

val writePlugins = taskKey[Unit]("adds sbt-source-deps to the a and b builds")
writePlugins := Seq("a", "b").foreach { dir =>
  IO.write(
    baseDirectory.value / dir / "project/plugins.sbt",
    s"""addSbtPlugin("br.com.mobilemind" % "sbt-source-deps" % "${sys.props("plugin.version")}")\n"""
  )
}
