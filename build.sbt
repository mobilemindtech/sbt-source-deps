ThisBuild / organization := "br.com.mobilemind"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-source-deps",
    scalaVersion := "3.8.4",
    // scaladoc cannot read lmcoursier's @data classes (used to force versions)
    Compile / doc / sources := Nil,
    scriptedLaunchOpts ++= Seq("-Xmx1024M", "-Dplugin.version=" + version.value),
    scriptedBufferLog := false
  )
