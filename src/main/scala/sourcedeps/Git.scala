package sourcedeps

import java.io.File
import sbt.util.Logger

private[sourcedeps] object Git:
  /** Tracked + untracked (non-ignored) files of the work tree containing `dir`, relative to `dir`. */
  def listFiles(dir: File): Option[Seq[String]] =
    val r = Proc.run(Seq("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "."), dir)()
    if !r.ok then None
    else Some(r.out.mkString("\n").split('\u0000').toSeq.map(_.trim).filter(_.nonEmpty).distinct)

  def head(dir: File): Option[String] =
    val r = Proc.run(Seq("git", "rev-parse", "HEAD"), dir)()
    if r.ok then r.out.headOption.map(_.trim) else None

  /** Makes sure `dir` is a clone of `url` checked out at `ref`. Only fetches when `fetch` is true or the ref is unknown. */
  def sync(url: String, ref: String, dir: File, fetch: Boolean, log: Logger): Unit =
    if !new File(dir, ".git").exists then
      log.info(s"[source-deps] cloning $url into $dir")
      dir.getParentFile.mkdirs()
      Proc.checked(Seq("git", "clone", "--quiet", normalize(url), dir.getAbsolutePath), dir.getParentFile)
    else if fetch then
      log.info(s"[source-deps] fetching $url")
      Proc.checked(Seq("git", "fetch", "--quiet", "--tags", "--force", "origin"), dir)

    val target = resolve(dir, ref).orElse {
      if fetch then None
      else
        log.info(s"[source-deps] ref '$ref' not found locally, fetching $url")
        Proc.checked(Seq("git", "fetch", "--quiet", "--tags", "--force", "origin"), dir)
        resolve(dir, ref)
    }.getOrElse(throw ProcessFailed(s"git ref '$ref' not found in $url"))

    if !head(dir).contains(target) then
      log.info(s"[source-deps] checking out $ref (${target.take(10)}) in $dir")
      Proc.checked(Seq("git", "checkout", "--quiet", "--detach", target), dir)

  /** `java.net.URI` renders local paths as `file:/x`, which git reads as an ssh host. */
  private def normalize(url: String): String =
    if url.startsWith("file:/") && !url.startsWith("file://") then "file://" + url.stripPrefix("file:") else url

  /** Resolves a ref, preferring the remote branch so a fetch is enough to move a branch ref. */
  private def resolve(dir: File, ref: String): Option[String] =
    val candidates = if ref == "HEAD" then Seq("origin/HEAD", "HEAD") else Seq(s"origin/$ref", ref)
    candidates.iterator
      .map(c => Proc.run(Seq("git", "rev-parse", "--verify", "--quiet", s"$c^{commit}"), dir)())
      .collectFirst { case r if r.ok && r.out.nonEmpty => r.out.head.trim }
