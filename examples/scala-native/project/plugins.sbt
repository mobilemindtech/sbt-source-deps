// Replaced by _checkouts/scala-native: the version does not matter (this one is published nowhere).
// Without the checkout, use a released version (0.5.12).
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.13-SNAPSHOT")

// sbt-source-deps for this build: nativelib, javalib, scala3lib, ... and the compiler plugin
// nscplugin, which sbt-scala-native adds, come from _checkouts/scala-native too
addSbtPlugin("br.com.mobilemind" % "sbt-source-deps" % "0.1.0-SNAPSHOT")
