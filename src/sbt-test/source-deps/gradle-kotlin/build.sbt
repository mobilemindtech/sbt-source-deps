scalaVersion := "3.8.4"
// Kotlin DSL + Gradle's scala plugin (a Scala 2.13 library, used from Scala 3): published without a Scala suffix
sourceDependencies += SourceDep.local(file("greeter"))
