ThisBuild / organization := "example"
ThisBuild / version := "0.9.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.16"
ThisBuild / crossScalaVersions := Seq("2.13.16", "3.8.4")

lazy val util = project.settings(name := "greeter-util")
// a project-level Scala version: the consumer's must still win (crossScalaVersions allows it)
lazy val core = project.dependsOn(util).settings(
  name := "greeter",
  scalaVersion := "2.13.16",
  crossScalaVersions := Seq("2.13.16", "3.8.4"),
  libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "2.4.0"
)
lazy val root = (project in file(".")).aggregate(util, core).settings(publish / skip := true)
