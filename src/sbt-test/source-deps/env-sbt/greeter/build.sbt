organization := "example"
name := "greeter"
version := "1.0.0"
scalaVersion := "3.8.4"

// the message comes from the environment of the build
Compile / sourceGenerators += Def.task {
  val out = (Compile / sourceManaged).value / "Greeter.scala"
  val message = sys.env.getOrElse("GREETING", "unset")
  IO.write(out, s"package greeter\n\nobject Greeter:\n  def message(): String = \"$message\"\n")
  Seq(out)
}.taskValue
