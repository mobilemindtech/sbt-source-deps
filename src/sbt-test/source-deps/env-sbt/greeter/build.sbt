organization := "example"
name := "greeter"
version := "1.0.0"
scalaVersion := "3.8.4"

// the message comes from a system property or the environment of the build
Compile / sourceGenerators += Def.task {
  val out = (Compile / sourceManaged).value / "Greeter.scala"
  val message = sys.props.get("greeting").orElse(sys.env.get("GREETING")).getOrElse("unset")
  IO.write(out, s"package greeter\n\nobject Greeter:\n  def message(): String = \"$message\"\n")
  Seq(out)
}.taskValue
