import scala.scalanative.unsafe.*

@main def hello(): Unit =
  // a pointer API from nativelib, compiled from _checkouts/scala-native
  val n = stackalloc[CInt]()
  !n = 42
  println(s"Hello from a Scala Native built from source: ${!n} (an int is ${sizeOf[CInt]} bytes)")
