plugins {
  scala
  `java-library`
}

group = "example"
version = "1.0.0"

repositories { mavenCentral() }

dependencies {
  implementation("org.scala-lang:scala-library:2.13.16")
}

// a Scala 2.13 library: Gradle 9.8 fails to compile Scala 3.8 ("8 is not a valid choice for
// -Xunchecked-java-output-version"), whatever the Java target
java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}
