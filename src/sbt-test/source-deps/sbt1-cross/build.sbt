// one dependency built with sbt 1.x, consumed by projects on two Scala binary versions
def app(name: String, scala: String) =
  Project(name, file(name)).settings(
    scalaVersion := scala,
    Compile / unmanagedSourceDirectories += (LocalRootProject / baseDirectory).value / "shared",
    sourceDependencies += SourceDep.local((LocalRootProject / baseDirectory).value / "greeter")
  )

lazy val app213 = app("app213", "2.13.16")
lazy val app3 = app("app3", "3.8.4")

lazy val root = (project in file(".")).aggregate(app213, app3)
