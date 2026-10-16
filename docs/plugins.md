# sbt plugins from source

Use a local or patched sbt plugin, together with the libraries it brings, without publishing it to
`~/.ivy2` or `~/.m2`. Edit the plugin's code and test it from your project right away. The example
throughout is **Scala Native**: its sbt plugin, compiler plugin and runtime libraries, all from a clone.

A ready-to-run copy of the setup is in [`examples/scala-native`](../examples/scala-native).

- [Setup](#setup)
- [What happens](#what-happens)
- [Editing](#editing)
- [How the meta-build uses the checkout](#how-the-meta-build-uses-the-checkout)
- [Requirements and limits](#requirements-and-limits)
- [Troubleshooting](#troubleshooting)

## Setup

An sbt plugin is a dependency of your build's **meta-build** (`project/`), which is an sbt build too.
sbt-source-deps goes in both: in the meta-build's own plugins, so `project/plugins.sbt` can use
checkouts, and in `project/plugins.sbt`, for your build.

```
app/
├── .sbtopts                      -J-Xmx6G
├── project/
│   ├── build.properties          sbt.version=2.0.10
│   ├── project/plugins.sbt       addSbtPlugin("br.com.mobilemind" % "sbt-source-deps" % "<version>")
│   └── plugins.sbt               addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.13-SNAPSHOT")
│                                 addSbtPlugin("br.com.mobilemind" % "sbt-source-deps" % "<version>")
├── build.sbt                     scalaVersion := "3.8.4"
│                                 enablePlugins(ScalaNativePlugin)
└── _checkouts/
    └── scala-native -> ~/src/scala-native
```

```
app$ mkdir -p _checkouts && ln -s ~/src/scala-native _checkouts/scala-native
app$ sbt run
```

- The declared plugin version does not matter while the checkout is there: `0.5.13-SNAPSHOT` is
  published nowhere. Without the checkout, put a released version back (`0.5.12`).
- `scalaVersion` 3.8.4 is the version sbt 2.0.10 runs on, which Scala Native builds its sbt 2 plugin
  with. Any version in Scala Native's `crossScalaVersions` works for your code.

## What happens

On the first start (several minutes; later starts take about as long as loading the plugin's build
once):

```
[info] [source-deps] building app/_checkouts/scala-native with sbt (first build)
[info] [source-deps] built app/_checkouts/scala-native in 3 min 20 s: 5 module(s), version suffix +src.f8a720faf254
[info] [source-deps] _checkouts: nativelib3 (scala-native, Scala 3.8.4) replaces its published module in app-root
[info] [source-deps] _checkouts: javalib3 (scala-native, Scala 3.8.4) replaces its published module in app-root
[info] [source-deps] _checkouts: nscplugin3 (scala-native, Scala 3.8.4) replaces its published compiler plugin in app-root
...
Hello from a Scala Native built from source: 42 (an int is 4 bytes)
```

| Level | Uses from the checkout | How |
| --- | --- | --- |
| meta-build (`project/`) | `sbt-scala-native`, with `tools`, `nir`, `util`, `test-runner` | Built in a separate sbt and published to the isolated repository, only when their sources change ([below](#how-the-meta-build-uses-the-checkout)) |
| your build | `nativelib`, `clib`, `posixlib`, `javalib`, `auxlib`, `scala3lib`, `test-interface` | Loaded **in your build**: projects of your build, compiled incrementally |
| your build | `nscplugin` (compiler plugin) | Its jar is passed to scalac as `-Xplugin:` |

Scala Native's build has one project per module and Scala version (`nativelib2_12`, `nativelib2_13`,
`nativelib3`, ...). The project matching what the plugin declares for your Scala version is picked, and
built with your exact Scala version when its `crossScalaVersions` has it (Scala 3 reads TASTy only from
versions up to its own).

## Editing

| You change | Picked up | Cost (this setup) |
| --- | --- | --- |
| a runtime library (`nativelib`, `javalib`, ...) | next `compile`/`run` | incremental compilation, then linking |
| the compiler plugin (`nscplugin`) | next `compile`/`run` | the plugin's jar, then a full compilation of your code with it |
| the sbt plugin or its libraries (`sbt-scala-native`, `tools`, `nir`, `util`) | `reload` | the separate build (about 2 minutes), then loading again |

The compiler plugin is passed under a name holding its hash: zinc compares compiler options, not the
plugin's content, so this is what makes an edited plugin recompile your code.

## How the meta-build uses the checkout

The meta-build and your build would each load the plugin's whole build (113 projects for Scala
Native). By default the meta-build does not: it builds the checkout like a
[source dependency](usage.md#local-directories), in a separate sbt, and only:

- **when its sources change** (content hash, see [fingerprint](how-it-works.md#fingerprint)): a normal
  start costs a hash check, not a load;
- **the projects it needs**: those producing the plugins `project/plugins.sbt` declares, for the
  meta-build's Scala version, and the projects they depend on (5 modules out of 113 projects for Scala
  Native).

The separate build compiles in the checkout's own `target/` directories, as when you build it yourself.

**Memory**: while it runs, two sbt processes are alive, yours and the separate one, which reads the
checkout's own `.sbtopts` (Scala Native's asks for `-J-Xmx5G`). Together with yours (`-J-Xmx6G`), a
rebuild needs about 11 GB at its peak. On a machine with less free memory, give the separate build less
heap from `project/plugins.sbt` (a later `-J-Xmx` wins over the checkout's `.sbtopts`):

```scala
// project/plugins.sbt
sourceDepsCommands += BuildTool.Sbt -> Seq("sbt", "-J-Xmx3G")
```

With Scala Native on this machine:

| Meta-build | Start | Memory | `tools` edit |
| --- | --- | --- | --- |
| `published` (default) | ~39 s | ~3 GB | separate build ~1 min 50 s, then loading |
| `build` | ~57 s | ~5 GB | `reload` ~95 s, but see below |

To load the checkout into the meta-build instead (`build`), for example while working mostly on the
plugin itself:

```
sbt -Dsbt.source-deps.checkouts.meta=build
# or
SBT_SOURCE_DEPS_CHECKOUTS_META=build sbt
```

With `build`, the plugin's classes keep their name and version, and sbt 2's action cache does not see
that they changed: tasks of the plugin such as `nativeLink` can be served from the cache, unchanged,
after an edit of `tools`. With `published`, every edit gets a new version and the tasks run again.

## Requirements and limits

- **The plugin's build must run on sbt 2** and build the plugin for sbt 2 (Scala Native's does, with
  Scala 3.8.4). An sbt 1 build cannot be loaded; it is built apart like other tools, but then the
  libraries it adds come at its original version, which only a checkout of them can replace.
- **Memory**: give sbt more heap, `-J-Xmx6G` in `.sbtopts` (Scala Native asks for 5 GB itself), and
  count the separate build's while the plugin is rebuilt ([above](#how-the-meta-build-uses-the-checkout)).
- **First build**: everything you use is compiled from source, the Scala standard library for Native
  included.
- **One Scala version** per checkout project: your projects using the checkout should share it.
- The plugin's own plugins are loaded with yours (in `build` mode, and for the libraries in your build).
  Conflicting versions may fail to load.
- sbt names your implicit root project `<directory>-root` once `_checkouts/` holds a build.

## Troubleshooting

- **`OutOfMemoryError`**, often on `reload`: raise `-J-Xmx` in `.sbtopts`.
- **The machine runs out of memory while the plugin is rebuilt**: the separate sbt runs next to yours;
  lower its heap with `sourceDepsCommands` in `project/plugins.sbt` (see above).
- **An edit of the sbt plugin is ignored**: run `reload`; the meta-build is only compiled while loading.
- **`Error downloading org.scala-native:nativelib_native0.5_3:...`**: the library is not replaced. Check
  that `project/plugins.sbt` adds sbt-source-deps (for your build), and that the startup log lists
  `nativelib3 ... replaces its published module`.
- **The native binary did not change after editing `tools`** with `-Dsbt.source-deps.checkouts.meta=build`:
  sbt's cache served the old link; use the default mode.

See also [`_checkouts`](usage.md#editing-a-library-in-place-_checkouts) for libraries, and
[how it works](how-it-works.md#_checkouts).
