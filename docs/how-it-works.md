# How it works

```
 sbt update / compile
        │
        ▼
 sourceDepsResolved ── for each SourceDep (under a file lock) ─────────────────────────┐
        │                                                                              │
        │   1. checkout     git clone / checkout ref        (local: use the directory) │
        │   2. detect       build.mill? build.sbt? build.gradle? pom.xml? project.scala?│
        │   3. fingerprint  sha256(files + nested deps) → suffix "+src.<12 hex>"       │
        │   4. up to date?  repo/ already has versions ending in the suffix → done     │
        │   5. build        tool publishes into staging/ → republish with the suffix   │
        │   6. clean up     delete the previous suffix of this dependency              │
        └──────────────────────────────────────────────────────────────────────────────┘
        │
        ├─► allDependencies   += selected modules     (V+src.<hash>)
        ├─► csrConfiguration  forceVersions += every published module
        └─► resolvers         += .source-deps/repo
        │
        ▼
 coursier resolves as usual (transitive dependencies come from the POMs)
```

## Integration with sbt

sbt's dependency resolution is driven by `allDependencies`. In sbt 2, `allDependencies` is a
**task** (`libraryDependencies` is a setting), so a task can build something before resolution and
add the result. The plugin redefines:

```scala
allDependencies := Def.uncached(allDependencies.value ++ sourceDepsResolve.value)
```

`Def.uncached` matters. sbt 2 caches task results by default, and a cached `allDependencies` would
skip the up-to-date check whenever the build definition did not change, so edits in a dependency
would go unnoticed.

The isolated repository is added to `resolvers`, a setting, which is possible because its location
is fixed.

## Fingerprint

The fingerprint decides whether a dependency must be rebuilt. It is a SHA-256 over:

- a format version of the plugin (bumped when builders change in incompatible ways);
- the build tool;
- the variant (the consumer's Scala binary version for sbt dependencies, `default` otherwise);
- the environment (`sourceDepsEnv` plus the dependency's `.env(...)`), sorted by name, and the
  dependency's `.args(...)`;
- for each file of the project, sorted by path: its relative path and its **content**;
- for each [nested source dependency](#dependencies-that-use-the-plugin) recorded by the previous
  build: its id and its current suffix, computed the same way (recursively).

The files are listed as follows:

- **Inside a git work tree**: `git ls-files --cached --others --exclude-standard`, which gives tracked
  files plus untracked ones not ignored by `.gitignore`. Deleted tracked files count as deleted.
- **Otherwise**: a directory walk.
- **In both cases**, files under these directories are skipped: `.git`, `target`, `build`, `out`, `.bsp`,
  `.bloop`, `.metals`, `.idea`, `.vscode`, `.gradle`, `.mill`, `.scala-build`, `.source-deps` and
  `node_modules`.

Hashing contents (rather than timestamps) means that touching a file, switching branches back and
forth, or restoring a cache never causes a useless rebuild, and a real change is never missed.

### Hash cache

Reading every file on each `update` gets slow for large dependencies. The SHA-256 of each file is kept
in `<dependency>/file-hashes.tsv` with the file's size and modification time, and reused while both are
unchanged. Only new or modified files are read.

As in git's index, an entry is trusted only if the file was modified more than 2 seconds before it was
hashed. A file written in the same instant as the hash could otherwise change without its size or
(coarse) modification time changing. Such files are hashed again on the next check. A missing or
corrupt cache only costs a full read.

## Versions

The suffix is `+src.` followed by the first 12 hex digits of the fingerprint.

The suffix is computed again **after** the build, because the build may have recorded nested source
dependencies (on the first build, or when the dependency's own `sourceDependencies` changed). The
artifacts are published under that final suffix, so the next up-to-date check computes the same
value and does not rebuild.

**Why change the version at all?** If the jar were overwritten under the same version:

- coursier, sbt's `update` cache, zinc and IDEs could keep using the previous build;
- a JVM still holding the old jar (sbt's layered class loaders) could fail on a jar changing
  under its feet;
- the released `1.0.0` from Maven Central and the source build `1.0.0` would be indistinguishable,
  and whichever repository answers first would win.

A version derived from the content avoids all of this. It also makes the up-to-date check trivial:
if `repo/` already contains versions ending with the suffix, the sources were already built.

**Why `+`?** It is semver build metadata, so `1.0.0+src.abc` still reads as `1.0.0`. For coursier
this also means `1.0.0+src.abc` **compares equal** to `1.0.0` and **lower** than `1.0.1`, so ordinary
conflict resolution cannot be trusted to pick the source build. That is why versions are forced
(below).

## Publishing

Every build tool publishes with the project's own version into `<dependency>/staging`, a Maven
repository local to the dependency. The plugin then republishes the modules into `repo/` with the
suffix.

### sbt dependencies

The plugin generates a global settings directory (`-Dsbt.global.settings`) containing a copy of your
global `*.sbt` files and an `onLoad` hook. Once the build is loaded, the hook:

1. reads each project's `scalaVersion` and `crossScalaVersions` from the loaded state;
2. appends constant settings: the selected Scala version, `publishTo` (the staging repository) and
   `publishMavenStyle`, plus a marker key;
3. stops when every project has the marker, because `appendWithSession` reloads the session, which
   runs `onLoad` again.

Constants are used instead of `(ref / scalaVersion).value` because global settings also reach the
meta-build (`project/`), where self-references create cycles.

### Republishing

For every module found in `staging/`:

0. Timestamped SNAPSHOT files (`greeter-1.0.0-20261015.182452-1.jar`, written by `mvn deploy`) are
   renamed to `greeter-1.0.0-SNAPSHOT.jar`, keeping the latest build.
1. The coordinates are taken from the repository layout (`group/artifact/version/`), because raw POMs
   may contain placeholders such as `${revision}`.
2. Files are copied with the suffixed version in their names. Checksums, signatures and Gradle
   `.module` files are dropped.
3. The POM is rewritten:
   - the module's `<version>` is set to the suffixed version, and added when it was inherited from the
     parent;
   - the `<parent>` version is suffixed when the parent belongs to the same build;
   - `<dependency>` versions pointing at modules of the same build are suffixed, when written
     literally or as a placeholder other than `${project.version}` (which resolves by itself).
4. SHA-1 and MD5 checksums are regenerated.

This keeps the builders simple: they only need to publish to a directory, which every tool supports.

## Dependencies that use the plugin

A dependency built with sbt may declare `sourceDependencies` itself (app → middle → greeter). When
the plugin launches the nested sbt, it passes three system properties, which the plugin running inside
that build reads:

| Property | Effect in the nested build |
| --- | --- |
| `sbt.source-deps.dir` | Default of `sourceDepsDirectory`: nested dependencies are cloned, built and published in the **outer** working directory, so the outer build finds them in `repo/`. A dependency declared at several levels is built once. |
| `sbt.source-deps.record` | Each source dependency the nested build resolves is recorded in `<middle>/nested-<variant>/`: location, ref, subdirectory, tool, modules and Scala binary version. |
| `sbt.source-deps.chain` | The ids of the dependencies being built. A dependency already in the chain fails with `cycle between source dependencies: a -> b -> a` instead of waiting forever on its own lock. Chains deeper than 16 fail too. |

The outer build uses the records to:

- include each nested dependency's current suffix in the fingerprint, so a change in greeter rebuilds
  middle (whose POM must point at the new greeter);
- fetch nested git dependencies on `sourceDepsUpdate`;
- force the versions of the nested modules too, since they come transitively.

The properties are ignored by builds that do not use the plugin. A dependency using an older version
of the plugin, or built with sbt 1.x (the plugin requires sbt 2), keeps its nested dependencies in
its own `.source-deps`, and the outer build fails to resolve them.

## Forced versions

After resolution, the plugin knows every module the source build published, not only those selected
with `.modules(...)`. They are all added to coursier's forced versions:

```scala
csrConfiguration := Def.uncached {
  val conf = csrConfiguration.value
  conf.withForceVersions(conf.forceVersions ++ forced)
}
```

Forced versions apply anywhere in the graph. A library you depend on that was compiled against
`greeter 1.0.1` gets `greeter 1.0.0+src.<hash>`. This is the point of a source dependency, but it also
means binary compatibility is your responsibility, as with `dependencyOverrides`.

## Git

- **Clone**: `git clone <url> <dependency>/src`. The repository is cloned whole, without a shallow
  clone, so any ref can be checked out later.
- **Resolve ref**: `origin/<ref>` first, so that a branch follows the fetched remote, then `<ref>`
  (tags, commits). `HEAD` resolves to `origin/HEAD`.
- **Checkout**: `git checkout --detach <commit>`, only when HEAD differs. If tracked files have local
  edits (`git status --porcelain --untracked-files=no`), the checkout is refused with the list of
  edited files.
- **Fetch**: `git fetch --tags --force origin`, only on `sourceDepsUpdate`, or once when the ref is
  unknown locally.
- **No prompts**: git runs with `GIT_TERMINAL_PROMPT=0` and, unless already set,
  `GIT_SSH_COMMAND="ssh -o BatchMode=yes"`.
- URLs of the form `file:/path` (as produced by `java.net.URI`) are normalized to `file:///path`.

## Concurrency

Work on a dependency (checkout, fingerprint, build, clean up) runs under two locks:

- a `ReentrantLock` per dependency directory, shared by the whole JVM, for parallel tasks of the same
  sbt session (several projects declaring the same dependency);
- a `FileChannel` lock on `<dependency>/.lock`, for other processes such as another sbt session, Metals
  or a CI job sharing the directory.

The second session waits, then finds the fresh build and skips it.

## Processes

Tools run through `java.lang.ProcessBuilder`, with stdout and stderr merged and stdin closed. Each
line is logged at debug level, and the last 80 lines are logged as errors on failure. The working
directory is the dependency's project directory, and `sourceDepsEnv` is added to the environment.
