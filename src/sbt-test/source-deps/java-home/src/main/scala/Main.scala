import java.util.Properties

// each dependency records the JDK its build ran on: Maven and Gradle in a filtered resource,
// scala-cli through the class file version of a Java source compiled by javac
@main def check(expected: String): Unit =
  val loader = Thread.currentThread.getContextClassLoader
  def feature(version: String) = version.stripPrefix("1.").takeWhile(_.isDigit)
  def fromResource(name: String) =
    val props = Properties()
    val in = loader.getResourceAsStream(name)
    require(in != null, s"$name not found on the classpath")
    try props.load(in) finally in.close()
    feature(props.getProperty("java"))
  def fromClassFile(className: String) =
    val in = loader.getResourceAsStream(className.replace('.', '/') + ".class")
    require(in != null, s"$className not found on the classpath")
    val bytes = try in.readNBytes(8) finally in.close()
    (((bytes(6) & 0xff) << 8 | (bytes(7) & 0xff)) - 44).toString

  val actual = Seq(
    "maven" -> fromResource("jv-maven.properties"),
    "gradle" -> fromResource("jv-gradle.properties"),
    "scala-cli" -> fromClassFile("jvscalacli.JV")
  )
  val wrong = actual.filter(_._2 != expected)
  if wrong.nonEmpty then sys.error(s"expected JDK $expected, got: ${wrong.map((t, v) => s"$t=$v").mkString(", ")}")
  println(s"ok: ${actual.map((t, v) => s"$t=$v").mkString(", ")}")
