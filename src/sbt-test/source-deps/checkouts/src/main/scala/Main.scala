@main def check(expected: String): Unit =
  val actual = greeter.Greeter.message()
  if actual != expected then sys.error(s"expected '$expected' but the checkout returned '$actual'")
  // compiled with Scala 3 (the consumer's version), not 2.13: TASTy files are present
  if Thread.currentThread.getContextClassLoader.getResource("greeter/Greeter.tasty") == null then
    sys.error("greeter.Greeter was not compiled with Scala 3")
  println(s"ok: $actual")
