scalaVersion := "3.8.4"
// ${revision} + SNAPSHOT: deployed with timestamped file names and unresolved placeholders
sourceDependencies += SourceDep.local(file("greeter")).modules("greeter")
