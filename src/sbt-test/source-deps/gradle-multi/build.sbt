scalaVersion := "3.8.4"
// multi-project build: core depends on api through a project dependency
sourceDependencies += SourceDep.local(file("greeter")).modules("core")
