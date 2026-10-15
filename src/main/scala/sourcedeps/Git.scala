package sourcedeps

import java.io.File
import sbt.util.Logger

private[sourcedeps] object Git:
  /**
   * Git never runs attached to a terminal here (stdin is closed), so prompts for credentials would
   * hang or fail obscurely: make git and ssh fail fast instead. A user's own GIT_SSH_COMMAND wins.
   */
  private def env: Seq[(String, String)] =
    Seq("GIT_TERMINAL_PROMPT" -> "0") ++
      (if sys.env.contains("GIT_SSH_COMMAND") then Nil else Seq("GIT_SSH_COMMAND" -> "ssh -o BatchMode=yes"))

  private def run(dir: File, args: String*): Proc.Result = Proc.run("git" +: args, dir, env)()

  private def checked(dir: File, args: String*): Seq[String] = Proc.checked("git" +: args, dir, env)

  /** Tracked + untracked (non-ignored) files of the work tree containing `dir`, relative to `dir`. */
  def listFiles(dir: File): Option[Seq[String]] =
    val r = run(dir, "ls-files", "-z", "--cached", "--others", "--exclude-standard", ".")
    if !r.ok then None
    else Some(r.out.mkString("\n").split('\u0000').toSeq.map(_.trim).filter(_.nonEmpty).distinct)

  def head(dir: File): Option[String] =
    val r = run(dir, "rev-parse", "HEAD")
    if r.ok then r.out.headOption.map(_.trim) else None

  /** Makes sure `dir` is a clone of `url` checked out at `ref`. Only fetches when `fetch` is true or the ref is unknown. */
  def sync(url: String, ref: String, dir: File, fetch: Boolean, log: Logger, partial: Boolean = false): Unit =
    if !new File(dir, ".git").exists then
      log.info(s"[source-deps] cloning $url into $dir" + (if partial then " (partial clone)" else ""))
      dir.getParentFile.mkdirs()
      val filter = if partial then Seq("--filter=blob:none") else Nil
      checked(dir.getParentFile, Seq("clone", "--quiet") ++ filter ++ Seq(normalize(url), dir.getAbsolutePath)*)
    else if fetch then
      log.info(s"[source-deps] fetching $url")
      checked(dir, "fetch", "--quiet", "--tags", "--force", "origin")

    val target = resolve(dir, ref).orElse {
      if fetch then None
      else
        log.info(s"[source-deps] ref '$ref' not found locally, fetching $url")
        checked(dir, "fetch", "--quiet", "--tags", "--force", "origin")
        resolve(dir, ref)
    }.getOrElse(throw ProcessFailed(s"git ref '$ref' not found in $url"))

    if !head(dir).contains(target) then
      // a checkout would carry the edits over (or fail on a conflict): never build a mix of both
      val edits = checked(dir, "status", "--porcelain", "--untracked-files=no").filter(_.trim.nonEmpty)
      if edits.nonEmpty then
        throw ProcessFailed(
          s"cannot move the clone of $url to '$ref': it has local changes in $dir\n" +
            edits.take(20).mkString("\n") +
            "\nCommit, stash or discard them (or run sourceDepsClean to start over)."
        )
      log.info(s"[source-deps] checking out $ref (${target.take(10)}) in $dir")
      checked(dir, "checkout", "--quiet", "--detach", target)

  /** `java.net.URI` renders local paths as `file:/x`, which git reads as an ssh host. */
  private def normalize(url: String): String =
    if url.startsWith("file:/") && !url.startsWith("file://") then "file://" + url.stripPrefix("file:") else url

  /** Resolves a ref, preferring the remote branch so a fetch is enough to move a branch ref. */
  private def resolve(dir: File, ref: String): Option[String] =
    val candidates = if ref == "HEAD" then Seq("origin/HEAD", "HEAD") else Seq(s"origin/$ref", ref)
    candidates.iterator
      .map(c => run(dir, "rev-parse", "--verify", "--quiet", s"$c^{commit}"))
      .collectFirst { case r if r.ok && r.out.nonEmpty => r.out.head.trim }
