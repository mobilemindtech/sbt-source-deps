scalaVersion := "3.8.4"

// the JDK the dependencies are built with: SOURCE_DEPS_TEST_JDK when set (passed by the plugin's build),
// otherwise the JDK running sbt, so the test also runs where only one JDK is installed
val testJdk = settingKey[File]("JDK the dependencies are built with")
testJdk := file(sys.props.getOrElse("source-deps.test.jdk", sys.props("java.home")))

sourceDependencies := Seq(
  SourceDep.local(file("jv-maven")).javaHome(testJdk.value),
  SourceDep.local(file("jv-gradle")).javaHome(testJdk.value),
  SourceDep.local(file("jv-scalacli")).javaHome(testJdk.value)
)

val check = taskKey[Unit]("Checks every dependency was built with testJdk")
check := Def.uncached(Def.taskDyn {
  // the JDK's feature version, from its `release` file (JAVA_VERSION="17.0.17")
  val line = IO.readLines(testJdk.value / "release").find(_.startsWith("JAVA_VERSION=")).get
  val expected = line.stripPrefix("JAVA_VERSION=").stripPrefix("\"").takeWhile(_.isDigit)
  streams.value.log.info(s"expected JDK: $expected (${testJdk.value})")
  (Compile / run).toTask(s" $expected")
}.value)
