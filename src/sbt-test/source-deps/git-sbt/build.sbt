import scala.sys.process.*

scalaVersion := "3.8.4"
sourceDependencies += SourceDep.git((baseDirectory.value / "remote").toURI.toString, ref = "main")

def git(dir: File, args: String*): Unit =
  val cmd = Seq("git", "-c", "user.name=test", "-c", "user.email=test@example.com") ++ args
  if Process(cmd, dir).! != 0 then sys.error(s"git ${args.mkString(" ")} failed")

val initRemote = taskKey[Unit]("creates the upstream repository with the v1 sources")
initRemote := {
  val remote = baseDirectory.value / "remote"
  IO.copyDirectory(baseDirectory.value / "greeter-src", remote)
  git(remote, "init", "--quiet", "-b", "main")
  git(remote, "add", ".")
  git(remote, "commit", "--quiet", "-m", "v1")
}

val commitV2 = taskKey[Unit]("pushes a v2 commit to the upstream branch")
commitV2 := {
  val remote = baseDirectory.value / "remote"
  IO.copyFile(baseDirectory.value / "changes" / "Greeter.scala", remote / "src/main/scala/Greeter.scala")
  git(remote, "commit", "--quiet", "-am", "v2")
}
