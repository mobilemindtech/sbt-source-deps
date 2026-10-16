@main def check(expected: String): Unit =
  val actual = kit.Kit.message()
  if actual != expected then sys.error(s"expected '$expected' but the checkout returned '$actual'")
  println(s"ok: $actual")
