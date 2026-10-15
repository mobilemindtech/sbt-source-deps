package sourcedeps

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.security.MessageDigest

private[sourcedeps] object Hashing:
  def sha256(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8)))

  def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  /** Directories that hold build output or tool state and never affect the result. */
  val ignoredDirs: Set[String] = Set(
    ".git", "target", "build", "out", ".bsp", ".bloop", ".metals", ".idea", ".vscode",
    ".gradle", ".mill", ".scala-build", ".source-deps", "node_modules"
  )

  /** Content hash of the source tree rooted at `root` (relative paths + file contents). */
  def fingerprint(root: File, extra: Seq[String]): String =
    val md = MessageDigest.getInstance("SHA-256")
    extra.foreach(e => md.update((e + "\n").getBytes(UTF_8)))
    listFiles(root).foreach { rel =>
      val f = new File(root, rel)
      md.update(rel.getBytes(UTF_8))
      md.update(0: Byte)
      if f.isFile then md.update(Files.readAllBytes(f.toPath)) else md.update("<deleted>".getBytes(UTF_8))
      md.update(0: Byte)
    }
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
