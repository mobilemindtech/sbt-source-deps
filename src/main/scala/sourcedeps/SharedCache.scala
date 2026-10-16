package sourcedeps

import java.io.File

/**
 * Defaults of the cache shared by every project of the machine. Builds and clones are keyed by the
 * dependency's location and fingerprint, so projects using the same dependency reuse one build.
 *
 * Overrides without editing any build, e.g. for CI:
 *   - `-Dsbt.source-deps.shared=false` or `SBT_SOURCE_DEPS_SHARED=false`: one `.source-deps` per build;
 *   - `-Dsbt.source-deps.cache=<dir>` or `SBT_SOURCE_DEPS_CACHE=<dir>`: another location.
 */
private[sourcedeps] object SharedCache:
  val SharedProp = "sbt.source-deps.shared"
  val SharedEnv = "SBT_SOURCE_DEPS_SHARED"
  val DirProp = "sbt.source-deps.cache"
  val DirEnv = "SBT_SOURCE_DEPS_CACHE"

  def enabledByDefault: Boolean =
    sys.props.get(SharedProp).orElse(sys.env.get(SharedEnv)).map(_.trim.toLowerCase) match
      case Some("false" | "0" | "no" | "off") => false
      case _                                  => true

  /** `~/.cache/sbt-source-deps` (or `$XDG_CACHE_HOME`), `~/Library/Caches` on macOS, `%LOCALAPPDATA%` on Windows. */
  def defaultDirectory: File =
    sys.props.get(DirProp).orElse(sys.env.get(DirEnv)).filter(_.trim.nonEmpty).map(new File(_)).getOrElse {
      val home = new File(sys.props("user.home"))
      val os = sys.props.getOrElse("os.name", "").toLowerCase
      val base =
        if os.startsWith("windows") then sys.env.get("LOCALAPPDATA").map(new File(_)).getOrElse(new File(home, "AppData/Local"))
        else if os.startsWith("mac") then new File(home, "Library/Caches")
        else sys.env.get("XDG_CACHE_HOME").filter(_.nonEmpty).map(new File(_)).getOrElse(new File(home, ".cache"))
      new File(base, "sbt-source-deps")
    }
