organization := "example"
name := "middle"
version := "1.0.0"
scalaVersion := "3.8.4"
// this dependency uses sbt-source-deps itself
sourceDependencies += SourceDep.local(file("../greeter"))
