import scala.sys.process.*

ThisBuild / scalaVersion := "3.8.4"

val remote = settingKey[String]("URL of the upstream repository")
ThisBuild / remote := ((LocalRootProject / baseDirectory).value / "remote").toURI.toString

// written by `commitV2`; the build is reloaded afterwards
def pinnedCommit(base: File): String =
  val f = base / "v2-commit.txt"
  if f.exists then IO.read(f).trim else "unknown"

def app(name: String, dep: Def.Initialize[SourceDep]) =
  Project(name, file(name)).settings(
    Compile / unmanagedSourceDirectories += (LocalRootProject / baseDirectory).value / "shared",
    sourceDependencies += dep.value
  )

lazy val byTag = app("byTag", Def.setting(
  SourceDep.git(remote.value, ref = "v1").subdir("modules/greeter").named("greeter-v1").partialClone
))
lazy val byCommit = app("byCommit", Def.setting(
  SourceDep.git(remote.value, ref = pinnedCommit((LocalRootProject / baseDirectory).value)).subdir("modules/greeter")
))
lazy val byHead = app("byHead", Def.setting(SourceDep.git(remote.value).subdir("modules/greeter").named("greeter-head")))

def git(dir: File, args: String*): String =
  val cmd = Seq("git", "-c", "user.name=test", "-c", "user.email=test@example.com") ++ args
  Process(cmd, dir).!!.trim

def greeterSource(dir: File) = dir / "modules/greeter/src/main/scala/Greeter.scala"

def writeGreeter(dir: File, message: String): Unit =
  IO.write(greeterSource(dir), s"package greeter\n\nobject Greeter:\n  def message(): String = \"$message\"\n")

def commit(dir: File, message: String): String =
  writeGreeter(dir, message)
  git(dir, "add", ".")
  git(dir, "commit", "--quiet", "-m", message)
  git(dir, "rev-parse", "HEAD")

val initRemote = taskKey[Unit]("creates the upstream repository: v1 (tagged), v2 (pinned by commit), v3")
val commitV4 = taskKey[Unit]("pushes v4 to the upstream branch")
val editClone = taskKey[Unit]("edits a tracked file inside the clone of byHead")
val discardEdit = taskKey[Unit]("discards the edit made by editClone")
val checkPartial = taskKey[Unit]("checks that byTag's clone is a partial clone")

// helper tasks live in the root project only: bare settings would apply to every subproject
lazy val root = (project in file(".")).aggregate(byTag, byCommit, byHead).settings(
  initRemote := {
    val dir = baseDirectory.value / "remote"
    IO.copyDirectory(baseDirectory.value / "upstream", dir)
    git(dir, "init", "--quiet", "-b", "main")
    // let file:// clones honour --filter
    git(dir, "config", "uploadpack.allowFilter", "true")
    commit(dir, "v1")
    git(dir, "tag", "v1")
    IO.write(baseDirectory.value / "v2-commit.txt", commit(dir, "v2"))
    commit(dir, "v3")
    ()
  },
  commitV4 := { commit(baseDirectory.value / "remote", "v4"); () },
  editClone := writeGreeter(baseDirectory.value / ".source-deps/greeter-head/src", "local edit"),
  checkPartial := {
    val filter = git(baseDirectory.value / ".source-deps/greeter-v1/src", "config", "--get", "remote.origin.partialclonefilter")
    if filter != "blob:none" then sys.error(s"expected a blob:none partial clone, got '$filter'")
  },
  discardEdit := { git(baseDirectory.value / ".source-deps/greeter-head/src", "checkout", "--", "."); () }
)
