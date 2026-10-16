ThisBuild / scalaVersion := "3.8.4"
// the plugin's build disables the shared cache for the tests: enable it, inside the test directory
ThisBuild / sourceDepsShared := true
ThisBuild / sourceDepsSharedDirectory := (LocalRootProject / baseDirectory).value / "shared-cache"

// the same dependency with different options: one version each, neither deletes the other's
lazy val a = project.settings(sourceDependencies += SourceDep.local(file("greeter").getAbsoluteFile).env("GREETING" -> "a"))
lazy val b = project.settings(sourceDependencies += SourceDep.local(file("greeter").getAbsoluteFile).env("GREETING" -> "b"))

val checkVersions = inputKey[Unit]("Checks how many versions of the dependency are published")
checkVersions := {
  val expected = Def.spaceDelimited().parsed.head.toInt
  val dir = sourceDepsDirectory.value / "repo" / "example" / "greeter_3"
  val versions = Option(dir.listFiles()).getOrElse(Array.empty[File]).filter(_.isDirectory).map(_.getName).sorted
  if versions.length != expected then sys.error(s"expected $expected versions in $dir, found: ${versions.mkString(", ")}")
}
