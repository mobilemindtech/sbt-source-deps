// one project per Scala version for each module, like Scala Native's build
ThisBuild / organization := "example"
ThisBuild / version := "0.3.0-SNAPSHOT"

lazy val lib2_13 = project.settings(name := "kit", scalaVersion := "2.13.16", crossScalaVersions := Seq("2.13.16"))
// builds with 3.9.0 by default: a 3.8.4 consumer cannot read its TASTy unless it is aligned to 3.8.4
lazy val lib3 = project.settings(name := "kit", scalaVersion := "3.9.0", crossScalaVersions := Seq("3.8.4", "3.9.0"))

// a compiler plugin, published per full Scala version (like nscplugin); marker2_13 is never built
lazy val marker2_13 = project.settings(name := "marker", crossVersion := CrossVersion.full, scalaVersion := "2.13.16")
lazy val marker3 = project.settings(
  name := "marker",
  crossVersion := CrossVersion.full,
  scalaVersion := "3.9.0",
  crossScalaVersions := Seq("3.8.4", "3.9.0"),
  libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % Provided
)

lazy val root = (project in file(".")).aggregate(lib2_13, lib3, marker2_13, marker3).settings(publish / skip := true)
