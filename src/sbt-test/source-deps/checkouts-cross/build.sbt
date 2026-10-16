scalaVersion := "3.8.4"
// published nowhere: provided by _checkouts/kit, which has one project per Scala version
libraryDependencies += "example" %% "kit" % "1.0.0"
addCompilerPlugin(("example" % "marker" % "1.0.0").cross(CrossVersion.full))
scalacOptions += s"-P:marker:${(baseDirectory.value / "marker-ran").getAbsolutePath}"
