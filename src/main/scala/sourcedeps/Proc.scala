package sourcedeps

import java.io.{BufferedReader, File, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** A user-facing failure: sbt shows the message only, without a stack trace. */
final class ProcessFailed(msg: String) extends RuntimeException(msg) with scala.util.control.NoStackTrace

private[sourcedeps] object Proc:
  final case class Result(exitCode: Int, out: Seq[String]):
    def ok: Boolean = exitCode == 0

  /** Runs a command (stdout and stderr merged, stdin closed), streaming each line to `onLine`; never throws on a non-zero exit. */
  def run(cmd: Seq[String], cwd: File, env: Seq[(String, String)] = Nil)(onLine: String => Unit = _ => ()): Result =
    val lines = mutable.ArrayBuffer.empty[String]
    val pb = ProcessBuilder(cmd.asJava).directory(cwd).redirectErrorStream(true)
    env.foreach((k, v) => pb.environment.put(k, v))
    try
      val p = pb.start()
      p.getOutputStream.close()
      val reader = BufferedReader(InputStreamReader(p.getInputStream, UTF_8))
      try
        var line = reader.readLine()
        while line != null do
          lines += line
          onLine(line)
          line = reader.readLine()
      finally reader.close()
      Result(p.waitFor(), lines.toVector)
    catch case e: java.io.IOException => Result(127, lines.toVector :+ s"failed to run ${cmd.head}: ${e.getMessage}")

  /** Runs a command and returns its output lines, failing with the full output otherwise. */
  def checked(cmd: Seq[String], cwd: File, env: Seq[(String, String)] = Nil): Seq[String] =
    val r = run(cmd, cwd, env)()
    if !r.ok then throw ProcessFailed(s"`${cmd.mkString(" ")}` failed (exit ${r.exitCode}) in $cwd:\n${r.out.mkString("\n")}")
    r.out
