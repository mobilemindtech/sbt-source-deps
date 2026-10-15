scalaVersion := "3.8.4"
sourceDependencies += SourceDep.local(file("greeter"))
ThisBuild / sourceDepsEnv := Map("GREETING" -> "v1")
