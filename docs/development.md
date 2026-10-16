# Development

## Building

```
sbt compile
sbt publishLocal        # br.com.mobilemind:sbt-source-deps:0.1.0-SNAPSHOT (sbt 2)
sbt scripted            # all integration tests
sbt "scripted source-deps/local-maven"
```

The plugin targets sbt 2.0.x and is compiled with Scala 3.8.4, the version sbt 2.0.10 runs on.

Scaladoc needs `"io.github.alexarchambault" %% "data-class" % "0.2.8" % Provided` on the classpath
(see "Scaladoc and `lmcoursier`" below). Keep that exact version.

## Source layout

| File | Responsibility |
| --- | --- |
| `SourceDepsPlugin.scala` | Keys, defaults, and wiring into `resolvers`, `allDependencies` and `csrConfiguration` |
| `SourceDep.scala` | User-facing model: `SourceDep`, `SourceLocation`, `BuildTool` |
| `Engine.scala` | Per-dependency flow: lock, checkout, fingerprint, build, republish, select modules, clean up, state files |
| `Nested.scala` | Dependencies whose own build uses the plugin: system properties, records, cycle chain |
| `Builders.scala` | Tool detection, launch commands, and one builder per tool (`SbtBuilder`, `MavenBuilder`, ...) |
| `Republish.scala` | Staging → isolated repository: suffixed versions, POM rewriting, checksums |
| `Poms.scala` | Lists modules of a Maven repository directory; reads POM packaging |
| `Git.scala` | Clone, fetch, ref resolution, checkout, `ls-files` |
| `Hashing.scala` | Fingerprint and the list of skipped directories |
| `Proc.scala` | Process execution (`ProcessBuilder`, merged output, closed stdin) |

## Tests

Scripted tests live in `src/sbt-test/source-deps/`. Each is a consumer project with a `greeter`
dependency and a `Main` that fails unless the dependency returns the expected message:

```
> run v1                                     # first build
$ exists .source-deps/repo/example/greeter_3
> sourceDepsStatus                           # "up to date"
> run v1                                     # no rebuild
$ copy-file changes/Greeter.scala greeter/...
> run v2                                     # rebuilt after the change
```

| Test | What it covers in addition |
| --- | --- |
| `local-sbt` | Cross-build selection (library defaults to 2.13, consumer on 3); forced version over a declared `greeter % "9.9.9"` that exists nowhere; `sourceDepsClean` removes the artifacts and the next compile rebuilds |
| `git-refs` | Tag, commit and `HEAD` refs of one repository, each with `.subdir(...)`, two with `.named(...)`; the tag uses `.partialClone`; the clone is not moved while it has local edits |
| `nested-sbt` | The dependency (`middle`) uses the plugin for its own dependency (`greeter`): built into the consumer's directory, `greeter` forced over a declared `9.9.9`, a change in `greeter` rebuilds `middle` |
| `nested-cycle` | `a` → `b` → `a` fails with a cycle error |
| `sbt1-cross` | Dependency built with sbt 1.13; two consumer projects (Scala 2.13 and 3) get one variant each, both rebuilt after a change |
| `git-sbt` | Clone of a git repository created by the test; a new commit on the branch is ignored until `sourceDepsUpdate` |
| `maven-revision` | `${revision}` with a `-SNAPSHOT` version, without `flatten-maven-plugin`: timestamped files and placeholders in the deployed POMs |
| `gradle-multi` | Multi-project build, `core` depends on `project(':api')`; a change in `api` comes through `core` |
| `mill-cross` | `Cross[...]` modules for 2.13 and 3, consumed by a 2.13 and a 3 project: each gets only its own artifacts |
| `gradle-kotlin` | `build.gradle.kts` with the `scala` plugin (a Scala 2.13 library, used from Scala 3) |
| `env-sbt` | `sourceDepsEnv` reaches the build (a source generator reads it); changing it rebuilds; per-dependency `.env(...)` and `.args(...)`; `sourceDepsShowOutput` |
| `java-home` | `.javaHome(...)` reaches Maven, Gradle and scala-cli (each records the JDK it ran on); switching JDK rebuilds with nothing stale; no `.scala-build` in the dependency. Uses `SOURCE_DEPS_TEST_JDK` as the first JDK when set |
| `shared-cache` | Builds go to `sourceDepsSharedDirectory`, not `.source-deps`; two subprojects using the same dependency with different `.env` keep one version each, through rebuilds; `sourceDepsShared := false` goes back to `.source-deps` |
| `build-errors` | Message when the tool command does not exist, and when an sbt build publishes nothing (`publish / skip`) |
| `local-maven` | Reactor with parent; consumer selects `greeter` only; the change is in `greeter-api`, which comes transitively |
| `local-gradle` | Plain `java-library` without publishing configuration |
| `local-mill` | Mill 1.3.0 through the project's `./mill` bootstrap script |
| `local-scalacli` | `project.scala` with `publish.*` directives; falls back to the `scala` runner |

Requirements: `git`, `mvn`, `gradle` and scala-cli (or `scala` ≥ 3.5) on the `PATH`. The Mill test
downloads Mill and a JDK on the first run. A full `scripted` run takes about 20 minutes. Set
`SOURCE_DEPS_TEST_JDK` to a second JDK (e.g. 17) so that `java-home` checks switching JDKs:

```
SOURCE_DEPS_TEST_JDK=/usr/lib/jvm/java-17 sbt scripted
```

## Design decisions and findings

A record of the choices made and the problems found while building the plugin.

### Integration through `allDependencies` (2026-10-14)

- **Alternatives considered**:
  - `ProjectRef`/`RootProject(uri(...))`, which only works for sbt dependencies and requires a
    compatible sbt;
  - `unmanagedJars`, which loses transitive dependencies;
  - publishing to `~/.m2`/`~/.ivy2`, which pollutes the global caches and can collide with real
    versions.
- **Chosen**: an isolated Maven repository plus `allDependencies` (a task in sbt 2). Transitive
  dependencies come from the POMs, and this works the same way for every tool.

### sbt 2 caches tasks by default

`allDependencies ++= sourceDepsResolve.value` does not compile in sbt 2 without a `JsonFormat`,
because the task would be cached. Worse, with a cached result the up-to-date check would never run.
All the plugin's tasks use `Def.uncached(...)`.

### Changing the version of an sbt build

> Superseded on 2026-10-15: sbt builds now publish through staging (see "Dependencies that use the
> plugin" below). The `onLoad` approach is still used for the Scala version and `publishTo`.

- `set every version ~= (_ + suffix)` creates a **cyclic reference** (`Global / version` ↔ project
  version).
- `set every version := "x"` gives all projects the same version and loses the original.
- **Chosen**: a generated global settings directory (`-Dsbt.global.settings`) with an `onLoad` hook
  that appends settings per project. Your global `*.sbt` files are copied next to it. This does not
  modify the dependency's directory.
- `appendWithSession` reloads the session, which runs `onLoad` again. The guard is to stop when every
  version already ends with the suffix.
- Global settings also apply to the **meta-build**. `(ref / scalaVersion).value` inside the
  appended settings created a cycle with `crossScalaVersions` there, so all values are now constants
  read with `extracted.get(...)`.

### `scala.sys.process` reported exit 127 for a successful process

Inside the sbt 2 JVM, `Process(...).!(logger)` returned 127 although the child process exited with 0
(checked with a wrapper script). Switching to `java.lang.ProcessBuilder` (merged output, closed
stdin) fixed it. The root cause was not investigated further.

### coursier ordering of `+src` versions

| Comparison | Result |
| --- | --- |
| `1.0.0+src.x` vs `1.0.0` | equal |
| `1.0.0+src.x` vs `1.0.1` | lower |
| `1.0.0+src.a` vs `1.0.0+src.b` | equal |

Conflict resolution alone could therefore pick a transitive release over the source build. Every
published module is forced through `csrConfiguration.withForceVersions` (`dependencyOverrides` is a
setting in sbt 2, so it cannot depend on the build's result).

### Staging and republishing

Changing the version is tool-specific and invasive: `mvn versions:set` rewrites POMs, and Gradle
needs `afterEvaluate` tricks. Publishing with the original version into a staging repository, then
copying with the suffix and rewriting the POMs, works the same way for Maven, Gradle, Mill and
scala-cli, and since 2026-10-15 for sbt too.

### Coordinates from the repository layout

Raw Maven POMs may contain `${revision}`. Coordinates are read from `group/artifact/version/`
instead of the XML.

### Maven `altDeploymentRepository` syntax

The legacy `id::default::url` syntax is used. `maven-deploy-plugin` 3.x warns about it, but 2.x does
not understand the new `id::url` syntax.

### `file:/` URLs and git

`java.net.URI#toString` renders `file:/path`, which git reads as an SSH host named `file`. It is
normalized to `file:///path`.

### Bare settings in a multi-project sbt 2 build (2026-10-15)

In sbt 2, settings written at the top level of `build.sbt` apply to **every** subproject, not only
the root. Helper tasks of the `git-refs` test ran in `byTag/` until they were moved into
`root.settings(...)`. Worth remembering for users' builds too: `sourceDependencies += ...` at the top
level adds the dependency to every project.

### Git without prompts and over local edits (2026-10-15)

- git runs with `GIT_TERMINAL_PROMPT=0`; `GIT_SSH_COMMAND="ssh -o BatchMode=yes"` is set only when the
  user has not set `GIT_SSH_COMMAND`, so custom SSH commands keep working.
- `git checkout --detach` carries non-conflicting local edits over to the new commit, which would build
  a mix of both. The checkout is refused when `git status --porcelain --untracked-files=no` is not empty.
  Untracked files are ignored on purpose (they are often scratch files); they still count in the
  fingerprint.
- `ProcessFailed` mixes in `NoStackTrace`: these are user-facing errors, and sbt printed a 30-line
  stack trace for each.

### `sourceDepsClean` prunes the repository

Deleting only the version directories left `repo/<group>/<artifact>/` behind. Removing a version now
also deletes artifact and group directories left without version subdirectories.

### Dependencies that use the plugin (2026-10-15)

Tried with app → middle (uses the plugin) → greeter. Findings, in order:

1. **Name clash.** The `publishTo` injected into middle's build and the resolver middle's own plugin
   adds were both named `source-deps`. Ivy picks repositories by name, so middle published into its
   own `.source-deps/repo` and the outer build saw nothing. The injected repository now has its own
   name.
2. **Isolated nested repository.** middle's POM pointed at `greeter 1.0.0+src.x`, which existed only in
   `middle/.source-deps`. Fixed by sharing the outer working directory through
   `-Dsbt.source-deps.dir`.
3. **No cascade.** middle's fingerprint ignored greeter. The nested plugin now records what it resolved
   (`-Dsbt.source-deps.record`), and the outer fingerprint includes each recorded dependency's current
   suffix, recursively. Nested modules are forced as well.
4. **The suffix depends on the build's output.** The records only exist after the first build, so a
   suffix computed before it would differ from every later check, and the build after it would be a
   useless rebuild. **Chosen**: sbt publishes into staging with its own version like the other tools,
   and the final suffix is computed after the build, then applied by `Republish`. This also removed the
   version override from the generated sbt settings.
5. **Cycles** would deadlock on the per-dependency file locks (the outer process holds `a`'s lock while
   `b`'s build waits for it). The chain of ids is passed down with `-Dsbt.source-deps.chain`.

- `publishTo` is a task in sbt 2, so the `onLoad` guard cannot read it. The generated file now defines
  its own marker key (`sourceDepsStagingApplied`), read with `extracted.getOpt`, which works on sbt 1
  and 2.
- Records keep the nested build's id verbatim (as `alias`): a relative `file("../greeter")` is resolved
  against the nested build's directory, so the outer build could not recompute it.

### Phase 3, part 2 (2026-10-15)

- **Maven SNAPSHOTs.** `mvn deploy` writes `greeter-1.0.0-20261015.182452-1.jar` into `1.0.0-SNAPSHOT/`
  (unique snapshots), so the staged module was not found and the build "published nothing".
  `Republish` now renames the latest timestamp to the plain `-SNAPSHOT` name first. Deployed POMs keep
  `${revision}` literally (no flatten plugin); the existing placeholder rewriting handled them.
- **Cross-published modules.** Mill `Cross` publishes `greeter_2.13` and `greeter_3` in one build, and
  `.modules("greeter")` selected both: sbt failed with "Conflicting cross-version suffixes". The
  selection now keeps, per module, only the artifact of the consumer's Scala binary version (or every
  artifact when none matches). All of them are still forced.
- **Partial rather than shallow clones.** `--depth 1` cannot check out an arbitrary commit, and
  `sourceDepsUpdate`/ref changes would need `--unshallow` or deepening. `--filter=blob:none` keeps the
  whole history (commits and trees) and downloads file contents on demand, so every existing code
  path works. Opt-in (`.partialClone`) because checking out another ref then needs the network. A
  `file://` remote needs `uploadpack.allowFilter=true`, otherwise git ignores the filter.
- **Missing tools.** `Proc.Result.notStarted` separates "could not launch" from "exited with 127", and
  the builders turn it into a message with the `sourceDepsCommands` line to add.
- In sbt 2, `Result` is an enum: match on `Result.Inc(i)` / `Result.Value(v)` in build files.

### Environment and JDK (2026-10-15)

- `sourceDepsEnv` was not part of the fingerprint, so changing `JAVA_HOME` did not rebuild anything.
  It is now. Nested records store the nested build's own `sourceDepsEnv`, so the outer build computes
  the same suffixes as the nested one.
- Checked by hand with `JAVA_HOME` set to JDK 17: the nested sbt still ran on Java 27. The sbt runner
  uses the `java` on the `PATH` and only switches with `--java-home`. The builders now pass
  `--java-home` to sbt, and put `$JAVA_HOME/bin` first on the `PATH` of every tool. Afterwards the
  nested sbt printed "Java 17.0.17". Not a scripted test, because it depends on the JDKs installed.
- Gradle 9.8 + Scala 3.8: `8 is not a valid choice for -Xunchecked-java-output-version`, with the Java
  arguments at `-source 17 -target 17`, on JDK 25 and 27, and with `ScalaCompile` options. Not
  investigated further; `gradle-kotlin` uses a Scala 2.13 library.

### `JAVA_HOME` with the other builders (2026-10-15)

Checked by hand with JDK 17, then by the `java-home` scripted test (dependencies record the JDK their
build ran on: a filtered resource with `java.version` for Maven and Gradle, the class file version of a
Java source for scala-cli):

- **Maven, Gradle 9.8**: honour `JAVA_HOME`. A `gradle/gradle-daemon-jvm.properties` in the build
  takes precedence (documented).
- **scala-cli**: compiles with `JAVA_HOME`, but `publish` kept the Java classes compiled by the
  previous JDK after `JAVA_HOME` changed (it printed "Compiling ... JVM (27)" and the class stayed at
  major 61; plain `compile` did recompile). The builder now passes `--workspace` to a directory under
  the dependency's work dir, emptied before each build: no stale state, and `.scala-build` is no longer
  written into the dependency's directory. Full rebuilds are fine because builds only run when the
  fingerprint changed.
- **Mill 1.3**: ignores `JAVA_HOME`. The launcher picks the daemon JVM from `mill-jvm-version`
  (`.mill-jvm-version`, `.config/mill-jvm-version`, or the `build.mill` header) and falls back to a
  default JDK it downloads; only the value `system` uses `java` from the `PATH`/`JAVA_HOME`. No
  environment variable or flag overrides it (`MillProcessLauncher.javaHome` in mill-runner-launcher
  1.3.0). Decision: do not write a `.mill-jvm-version` into the dependency (the plugin never modifies
  it); log a warning when `JAVA_HOME` is set for a Mill dependency, and document it.
- The test reads the second JDK from `SOURCE_DEPS_TEST_JDK` (the plugin's build passes it as
  `-Dsource-deps.test.jdk`); without it the test uses the JDK running sbt, which still checks the
  plumbing and the rebuild but not the switch between JDKs.

### Shared cache (2026-10-16)

- Asked for by the maintainer, enabled by default with an opt-out: `sourceDepsShared` (default from
  `-Dsbt.source-deps.shared` / `SBT_SOURCE_DEPS_SHARED`, else `true`) and `sourceDepsSharedDirectory`
  (`-Dsbt.source-deps.cache` / `SBT_SOURCE_DEPS_CACHE`, else the OS cache directory). The plugin's
  build passes `-Dsbt.source-deps.shared=false` to scripted, so the tests keep `<test>/.source-deps`
  and never touch the real cache; `shared-cache` turns it on inside its test directory.
- Ids were already global (git URL + ref, or the canonical local path, plus subdirectory), and the
  dependency locks were already file locks, so sharing needed three fixes:
  - **Slots.** State and nested records were per variant only: two consumers with different
    `.env`/`.args`/`sourceDepsEnv` deleted each other's version at every build (this already happened
    with two subprojects of one build). They are now per slot: variant + hash of env and args. A slot
    unused for 30 days (its state file is touched by each up-to-date check) is removed at the next build
    of that dependency, so abandoned options do not pile up.
  - **Index.** `Poms.published` scanned every POM of the repository for each dependency on every
    `update`, and could see a version another process was still copying or deleting. Each version now
    has `repo/.index/<suffix>`, written (atomically) after its files and deleted before them; the scan
    is only used on staging repositories. Format version 5: every dependency is rebuilt once.
  - **Repository lock.** Republishing and deleting versions run under `repo/.lock`. `prune` removes
    empty group/artifact directories, which could race with another process creating a version in the
    same group. The lock is never held while a tool runs: a nested build publishes into the same
    repository while the outer build waits for it.
- Not covered: two separate sbt processes in one scripted test (scripted runs a single build). The
  cross-process part relies on the file locks already used per dependency.
- On Windows, deleting the old version of a slot fails while another sbt session holds its jar open.

### Hash cache (2026-10-15)

- The fingerprint now hashes the SHA-256 of each file instead of feeding the bytes directly, so a
  cached per-file hash can stand in for the file. This changes every suffix once (format version 4).
- Racy entries (mtime within 2 s of the hashing time) are not trusted, the same trick as git's index.
- The cache is written to a temporary file and moved atomically: nested suffixes are computed without
  holding the dependency's lock.

### Scaladoc and `lmcoursier`

`publishLocal` of the plugin failed in `Compile / doc` ("undefined: new dataclass.data"). The
plugin uses `lmcoursier` classes declared with `@dataclass.data`, and their TASTy references that
annotation, but `librarymanagement-coursier` does not publish `data-class` as a dependency. Scaladoc
(unlike the compiler) resolves the annotation and fails when it is missing.

Fix: add `data-class` as a `Provided` dependency (compile-time only; `provided` scope in the POM is
not transitive). The version must match the annotation's constructor that `lmcoursier` was compiled
against: 0.2.7 has no Scala 3 artifact, 0.2.8 works, and 0.2.9/0.2.10/0.2.11 add constructor
parameters and fail with "wrong number of arguments ... dataclass.data". With 0.2.8 the Javadoc jar
contains the real Scaladoc, so no empty jar is needed for Maven Central.

## Status

**Done**
- Phase 1, sbt integration: `allDependencies` + isolated repository, fingerprint, sbt builder, local and
  git sources, file locks.
- Phase 2:
  - Maven, Gradle, Mill and scala-cli builders through staging and republishing;
  - forced versions;
  - Scala cross-version selection for sbt dependencies;
  - `sourceDepsEnv`;
  - documentation.
- Phase 3 (part 1):
  - tests for sbt 1.x dependencies, several consumer Scala versions, git tags/commits/`HEAD`,
    `.subdir`, `.named`, `sourceDepsClean`;
  - git without prompts; refuse to move a clone with local edits; no stack traces for user errors;
  - dependencies that use the plugin themselves (shared directory, cascading rebuilds, cycles); sbt
    builds go through staging too.
- Phase 3 (part 2):
  - tests for Maven `${revision}` + SNAPSHOT, Gradle multi-project, Mill `Cross` modules;
  - timestamped SNAPSHOTs; per-Scala-version selection of cross-published modules;
  - `.partialClone`; clear errors for a missing tool and for builds that publish nothing.
- Roadmap item 4: per-file hash cache (`file-hashes.tsv`), format version 4.
- Roadmap items 2–3: build duration logged; `sourceDepsShowOutput`; `.args`, `.env`, `.javaHome` per
  dependency (in the fingerprint and in nested records).
- Roadmap item 3 (part 1): shared cache, on by default (`sourceDepsShared`), with per-slot state,
  a version index and a repository lock.
- Roadmap item 1 (tests): Gradle Kotlin DSL + `scala` plugin; `sourceDepsEnv` in the fingerprint;
  `JAVA_HOME` honoured by sbt and on the `PATH`; `JAVA_HOME` with Maven, Gradle and scala-cli
  (`java-home`, which also fixed stale scala-cli classes); Mill warns that it ignores `JAVA_HOME`.

**Verified** with sbt 2.0.10 on JDK 27 (Linux), by the eighteen scripted tests above (the sbt 1.x
dependency with sbt 1.13.0).

**Not verified yet**
- Gradle builds publishing several Scala versions through variants (not supported);
- macOS and Windows (paths, wrapper scripts, `sh` fallback).

## Roadmap

Next steps, roughly in priority order:

1. **Tests for what is not verified yet** (list above).
2. **Publishing the plugin** (postponed by the maintainer, 2026-10-15): Scaladoc fixed (data-class
   0.2.8, Provided). Repository: <https://github.com/mobilemindtech/sbt-source-deps>. Still to decide
   and do: license, developer info, `homepage`/`scmInfo` in the POM, Central Portal namespace and GPG
   key (or `sbt-ci-release`), CI (GitHub Actions running `scripted`; needs Mill, Gradle, Maven and
   scala-cli), release to Maven Central.
3. **`ProjectRef` mode** for sbt 2 dependencies (same build, incremental compilation, IDE navigation
   into the working copy). The maintainer's goal: edit dependencies and test them without publishing
   to a local Maven repository, like rebar3's `_checkouts` (a directory whose projects override the
   declared dependencies). Start with a spike: `RootProject(file(...))` + `dependsOn` in sbt 2.0.10,
   Scala versions that differ, plugin conflicts, Metals/IntelliJ import. Projects must be known while
   the build loads, so this needs a helper called from `build.sbt`, not a `sourceDependencies` entry.
