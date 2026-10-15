object Main {
  def main(args: Array[String]): Unit = {
    val actual = greeter.Greeter.message()
    if (actual != args(0)) sys.error(s"expected '${args(0)}' but the dependency returned '$actual'")
    println(s"ok: $actual")
  }
}
