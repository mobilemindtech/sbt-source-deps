package greeter

object Greeter {
  // scala-xml is a dependency of the checkout project: it must reach the consumer's classpath
  def message(): String = Util.decorate(scala.xml.Text("v1").text)
}
