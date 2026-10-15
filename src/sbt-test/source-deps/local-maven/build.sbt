scalaVersion := "3.8.4"
// only the core module is requested: greeter-api must come transitively from the rewritten POM
sourceDependencies += SourceDep.local(file("greeter")).modules("greeter")
