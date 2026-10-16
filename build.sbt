ThisBuild / organization := "br.com.mobilemind"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-source-deps",
    scalaVersion := "3.8.4",
    // lmcoursier's TASTy references the @data annotation, which it does not publish as a dependency:
    // Scaladoc needs it on the classpath (compile-time only, not in the POM). 0.2.8 matches the
    // annotation signature lmcoursier was compiled against; 0.2.9+ add parameters and fail
    libraryDependencies += "io.github.alexarchambault" %% "data-class" % "0.2.8" % Provided,
    scriptedLaunchOpts ++= Seq("-Xmx1024M", "-Dplugin.version=" + version.value),
    // a second JDK for the java-home test (otherwise it uses the JDK running sbt)
    scriptedLaunchOpts ++= sys.env.get("SOURCE_DEPS_TEST_JDK").map("-Dsource-deps.test.jdk=" + _).toSeq,
    scriptedBufferLog := false
  )
