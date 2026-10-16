// one project per Scala version for each module, like Scala Native's build
ThisBuild / organization := "example"
ThisBuild / version := "0.3.0-SNAPSHOT"

lazy val lib2_13 = project.settings(name := "kit", scalaVersion := "2.13.16", crossScalaVersions := Seq("2.13.16"))
// builds with 3.9.0 by default: a 3.8.4 consumer cannot read its TASTy unless it is aligned to 3.8.4
// (projects of one name need distinct Scala versions: sbt 2 puts both outputs under target/out/jvm/scala-<version>/kit)
lazy val lib3 = project.settings(name := "kit", scalaVersion := "3.8.3", crossScalaVersions := Seq("3.8.3", "3.9.0"))

// like Scala Native's `_next` projects (an experimental compiler): only this one builds for the
// consumer's exact version, so it wins over lib3 although lib3 also has a version for binary 3
lazy val lib3next = project.settings(name := "kit", scalaVersion := "3.9.0", crossScalaVersions := Seq("3.8.4", "3.9.0"))

// a compiler plugin, published per full Scala version (like nscplugin); marker2_13 is never built
lazy val marker2_13 = project.settings(name := "marker", crossVersion := CrossVersion.full, scalaVersion := "2.13.16")
lazy val marker3 = project.settings(
  name := "marker",
  crossVersion := CrossVersion.full,
  scalaVersion := "3.9.0",
  crossScalaVersions := Seq("3.8.4", "3.9.0"),
  libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % Provided
)

lazy val root = (project in file(".")).aggregate(lib2_13, lib3, lib3next, marker2_13, marker3).settings(publish / skip := true)
