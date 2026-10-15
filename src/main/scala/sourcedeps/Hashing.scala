package sourcedeps

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, StandardCopyOption}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

private[sourcedeps] object Hashing:
  def sha256(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8)))

  def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  /** Directories that hold build output or tool state and never affect the result. */
  val ignoredDirs: Set[String] = Set(
    ".git", "target", "build", "out", ".bsp", ".bloop", ".metals", ".idea", ".vscode",
    ".gradle", ".mill", ".scala-build", ".source-deps", "node_modules"
  )

  /**
   * Content hash of the source tree rooted at `root`: relative paths plus the SHA-256 of each file.
   * With a `cache` file, the hash of a file whose size and modification time did not change is reused
   * instead of reading the file again.
   */
  def fingerprint(root: File, extra: Seq[String], cache: Option[File] = None): String =
    val previous = cache.map(HashCache.load).getOrElse(Map.empty)
    val current = Map.newBuilder[String, HashCache.Entry]
    val md = MessageDigest.getInstance("SHA-256")
    extra.foreach(e => md.update((e + "\n").getBytes(UTF_8)))
    listFiles(root).foreach { rel =>
      val f = new File(root, rel)
      md.update(rel.getBytes(UTF_8))
      md.update(0: Byte)
      if f.isFile then
        val entry = HashCache.hash(f, previous.get(rel))
        current += rel -> entry
        md.update(entry.sha256.getBytes(UTF_8))
      else md.update("<deleted>".getBytes(UTF_8))
      md.update(0: Byte)
    }
    val entries = current.result()
    cache.foreach(c => if entries != previous then HashCache.save(c, entries))
    hex(md.digest())

  /** Relative paths of the files that make up the project, sorted. */
  def listFiles(root: File): Seq[String] =
    Git.listFiles(root).getOrElse(walk(root)).filterNot(isIgnored).sorted

  private def isIgnored(rel: String): Boolean = rel.split('/').dropRight(1).exists(ignoredDirs)

  private def walk(root: File): Seq[String] =
    import java.nio.file.{FileVisitResult, Path, SimpleFileVisitor}
    import java.nio.file.attribute.BasicFileAttributes
    val base = root.toPath
    val out = Vector.newBuilder[String]
    Files.walkFileTree(
      base,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          if dir != base && ignoredDirs(dir.getFileName.toString) then FileVisitResult.SKIP_SUBTREE
          else FileVisitResult.CONTINUE
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
          if attrs.isRegularFile then out += base.relativize(file).toString.replace(File.separatorChar, '/')
          FileVisitResult.CONTINUE
    )
    out.result()

/**
 * Per-file hashes keyed by relative path, stored as `path TAB size TAB mtime TAB hashedAt TAB sha256`.
 *
 * Like git's index, an entry is only trusted when the file was last modified well before it was
 * hashed: a file written in the same instant as the hash (same size, same coarse mtime) could otherwise
 * change without being noticed.
 */
private[sourcedeps] object HashCache:
  final case class Entry(size: Long, mtime: Long, hashedAt: Long, sha256: String)

  /** Modification times closer than this to the hashing time are not trusted. */
  private val RacyWindowMillis = 2000L

  def hash(f: File, cached: Option[Entry]): Entry =
    val size = f.length()
    val mtime = Files.getLastModifiedTime(f.toPath).toMillis
    cached match
      case Some(e) if e.size == size && e.mtime == mtime && mtime < e.hashedAt - RacyWindowMillis => e
      case _ =>
        val hashedAt = System.currentTimeMillis()
        val digest = Hashing.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath)))
        Entry(size, mtime, hashedAt, digest)

  def load(file: File): Map[String, Entry] =
    if !file.isFile then Map.empty
    else
      try
        Files.readAllLines(file.toPath, UTF_8).asScala.iterator.flatMap { line =>
          line.split('\t') match
            case Array(path, size, mtime, hashedAt, sha) =>
              Some(path -> Entry(size.toLong, mtime.toLong, hashedAt.toLong, sha))
            case _ => None
        }.toMap
      catch case _: Exception => Map.empty // a corrupt cache only costs a full hash

  /** Written to a temporary file and moved, so concurrent readers never see a partial cache. */
  def save(file: File, entries: Map[String, Entry]): Unit =
    file.getParentFile.mkdirs()
    val tmp = File.createTempFile(file.getName, ".tmp", file.getParentFile)
    val lines = entries.toSeq.sortBy(_._1).collect {
      case (path, e) if !path.exists(c => c == '\t' || c == '\n' || c == '\r') =>
        s"$path\t${e.size}\t${e.mtime}\t${e.hashedAt}\t${e.sha256}"
    }
    Files.write(tmp.toPath, lines.asJava, UTF_8)
    Files.move(tmp.toPath, file.toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
