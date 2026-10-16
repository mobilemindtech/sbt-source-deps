scalaVersion := "3.8.4"
// published nowhere: only the checkouts in _checkouts/ can provide them
libraryDependencies ++= Seq(
  "example" %% "greeter" % "1.0.0", // an sbt 2 build: loaded with this build
  "example" % "jgreeter" % "1.0.0"  // a Maven build: built with Maven, its module forced
)
