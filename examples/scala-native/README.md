# Scala Native from source

A minimal Scala Native app whose sbt plugin, compiler plugin and runtime libraries come from a local
clone of Scala Native, through `_checkouts`. See [sbt plugins from source](../../docs/plugins.md).

```
git clone https://github.com/scala-native/scala-native.git ~/src/scala-native   # or your fork
cd examples/scala-native
mkdir -p _checkouts && ln -s ~/src/scala-native _checkouts/scala-native
sbt run
```

```
[info] [source-deps] built .../_checkouts/scala-native in 3 min 20 s: 5 module(s), version suffix +src...
[info] [source-deps] _checkouts: nativelib3 (scala-native, Scala 3.8.4) replaces its published module in scala-native-root
...
Hello from a Scala Native built from source: 42 (an int is 4 bytes)
```

Requirements: sbt 2.0.10 or later, clang/LLVM (for Scala Native), and a Scala Native clone whose build
runs on sbt 2 (the `main` branch does). sbt-source-deps must be published locally
(`sbt publishLocal` in this repository) until it is released.

Then edit the clone: a change in `nativelib` or `nscplugin` shows up on the next `run`, a change in the
sbt plugin or `tools` after `reload`.

To go back to the released Scala Native, remove the symlink, set a released version (`0.5.12`) in
`project/plugins.sbt` and `reload`.
