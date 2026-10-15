scalaVersion := "3.8.4"
// the source build must win over any other version of the module
libraryDependencies += "example" %% "greeter" % "9.9.9"
sourceDependencies += SourceDep.local(file("greeter"))
