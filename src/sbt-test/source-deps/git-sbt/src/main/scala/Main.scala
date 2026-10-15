@main def check(expected: String): Unit =
  val actual = greeter.Greeter.message
  if actual != expected then sys.error(s"expected '$expected' but the dependency returned '$actual'")
  println(s"ok: $actual")
