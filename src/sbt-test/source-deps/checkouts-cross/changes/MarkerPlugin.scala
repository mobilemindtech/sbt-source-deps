package marker

import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}

// writes the file named by -P:marker:<path>, so the test can see the plugin ran
class MarkerPlugin extends StandardPlugin:
  val name = "marker"
  override val description = "writes a marker file"
  override def init(options: List[String]): List[PluginPhase] =
    options.foreach(path => java.nio.file.Files.writeString(java.nio.file.Paths.get(path + "-v2"), "ran"))
    Nil
