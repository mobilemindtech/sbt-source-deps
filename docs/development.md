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
| `checkouts` | `_checkouts/greeter` (sbt 2, two modules, a project-level 2.13 `scalaVersion` with 3 in `crossScalaVersions`, `scala-xml`, uses this plugin itself) replaces `example:greeter:1.0.0`, published nowhere; compiled with Scala 3; works after `reload`; an edit recompiles incrementally; a non-sbt directory is skipped with a warning; nothing is published |
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

### Spike: projects of another build (`ProjectRef`, `_checkouts`) (2026-10-16)

Goal (maintainer): edit a dependency and test it from the consumer without publishing it anywhere, like
rebar3's `_checkouts`. Spike with sbt 2.0.10, a two-module library build (`core` -> `util`, its own
plugin and `ThisBuild` settings) and a consumer:

- `dependsOn(ProjectRef(file("../lib"), "core"))` works. External projects are addressed as
  `{file:/.../lib/}core/...` on the command line.
- **Incremental compilation** crosses the builds: changing `util/Util.scala` recompiles that file and
  its dependent in `core` only. Outputs of the external build go to the **consumer's**
  `target/out/jvm/...`, so the dependency's directory stays clean.
- **Plugins**: each build keeps its plugins (`core/assembly` works), and the consumer's auto plugins
  apply to the external projects too (`core/scalafmtOnCompile` exists). `ThisBuild` stays per build.
- **Scala versions**: sbt 2 refuses `app (Scala 3.8.4) depends on core (Scala 2.13.16)` unless
  `allowMismatchScala := true` (2.13 from 3 then works). Settings of external projects can be set from
  the consumer; appended in `onLoad` with `Project.extract(state).appendWithoutSession` they win over
  the dependency's own project-level `scalaVersion` (guard with a `State` attribute: the append runs
  `onLoad` again, which looped until `OutOfMemoryError`).
- **Substitution**: a project dependency already replaces the same module requested by
  `libraryDependencies` or transitively, with `dependsOn` at load time. When the edge is added after
  load (see below), coursier picks the higher published version (`greeter_3:1.0.0` wanted by another
  library over the local `0.9.0-SNAPSHOT`), dropping the local module's own dependencies (`scala-xml`).
  `dependencyOverrides` to the local version fixes it, the same idea as forced versions in jar mode;
  `excludeDependencies` does not (it drops the local module's dependencies too).
- **No `build.sbt` change needed**: external builds are only loaded when a project of the root build
  references them (`dependsOn`/`aggregate`), and project ids are unknown before loading. An
  `AutoPlugin.derivedProjects` can add a hidden project `dependsOn(RootProject(dir))` for each build in
  `<root>/_checkouts`, so sbt loads them; after loading, `Global / buildDependencies` (a setting,
  `BuildDependencies.addClasspath`) links each consumer project to the checkout projects whose
  `(organization, name)` it declares in `libraryDependencies`.
- **IDE**: `bspWorkspaceBuildTargets` lists the checkout projects (`core/Compile`, `util/Compile`, ...),
  so Metals indexes the working copy.

Implemented as proposed (branch `checkouts`, see "`_checkouts` mode" below). Proposed design (`_checkouts` mode, sbt 2 dependencies only):
1. Every directory in `<build root>/_checkouts/` (a symlink is fine) that holds an sbt 2 build is
   loaded with the consumer's build; non-sbt or sbt 1 builds are reported and skipped.
2. A consumer project that declares `libraryDependencies` on a module produced by a checkout project
   depends on that project instead (classpath edge), with its version forced (`dependencyOverrides`).
   A module only reached transitively must be declared to be replaced.
3. Checkout projects compile with the consumer's Scala version when their `crossScalaVersions` has
   one with the same binary version; a 2.13 project used from Scala 3 gets `allowMismatchScala`.
4. No `build.sbt` change: create or remove `_checkouts/<name>` and `reload`.
Limits: one Scala version per checkout project (two consumer projects on different Scala versions need
jar mode), plugin conflicts between builds, nested `_checkouts` ignored.

### `_checkouts` mode (2026-10-16, branch `checkouts`)

Findings while implementing the design above (see how-it-works.md for the mechanism):

- `derivedProjects` is not called for an implicit root project (a `build.sbt` without `project`
  definitions): the hidden project was never added. `extraProjects` is.
- A checkout using this plugin itself calls `extraProjects` again for its own build, and an absolute
  base outside that build fails (`directory ... is not contained in build root`). A JVM-wide "loading"
  flag relied on the root build loading first, which is false on `reload`. Fixed with a relative base:
  each build gets its own harmless hidden project, and only the root build's is used.
- `dependencyOverrides` did not stop coursier from fetching the declared `greeter_3:1.0.0`, which exists
  nowhere in the test; forcing the version through `csrConfiguration` (as for source dependencies) does.
- scripted runs `reload` before the commands; the `onLoad` guard (a state attribute) survived it and the
  Scala version was not aligned after the reload. `onUnload` now removes it.
- A project with a project-level `scalaVersion` gets `crossScalaVersions := Seq(scalaVersion)` by
  default and does not inherit `ThisBuild / crossScalaVersions`: such a project is only aligned when it
  declares its own `crossScalaVersions` (which a real cross-built project does).
- `getClass.getResource` from a top-level `@main` does not see the dependencies' resources under
  `run`; the tests use the context class loader.

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
- Roadmap item 3 (part 2, branch `checkouts`): `_checkouts` mode for sbt 2 libraries.
- Roadmap item 3 (part 1): shared cache, on by default (`sourceDepsShared`), with per-slot state,
  a version index and a repository lock.
- Roadmap item 1 (tests): Gradle Kotlin DSL + `scala` plugin; `sourceDepsEnv` in the fingerprint;
  `JAVA_HOME` honoured by sbt and on the `PATH`; `JAVA_HOME` with Maven, Gradle and scala-cli
  (`java-home`, which also fixed stale scala-cli classes); Mill warns that it ignores `JAVA_HOME`.

**Verified** with sbt 2.0.10 on JDK 27 (Linux), by the nineteen scripted tests above (the sbt 1.x
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
3. **`_checkouts` mode**: implemented on branch `checkouts`, waiting for the maintainer's review of the
   design (convention directory, no `build.sbt` change). Follow-ups:
   - non-sbt or sbt 1 entries in `_checkouts/`: build them in jar mode and force their modules
     (replace-only, without adding them to every project);
   - modules reached only transitively, without declaring them;
   - check the IntelliJ import by hand (BSP targets were checked with `bspWorkspaceBuildTargets`).
4. **sbt plugins from source** (requested 2026-10-16). Use the plugin in the meta-build
   (`project/project/plugins.sbt` + `sourceDependencies` in `project/plugins.sbt`, or a dedicated
   setting) to build an sbt plugin from a git/local checkout instead of `publishLocal`. Motivating case:
   a patched Scala Native (arena allocation for classes/case classes, not only pointers) to study and
   tweak. To investigate:
   - does it already work, since the meta-build is a project with `allDependencies`?
   - sbt 2 support of the plugin being built (the consumer runs sbt 2; Scala Native's sbt plugin may
     only target sbt 1.x), and the `_sbt2_3` artifact naming;
   - plugins that add libraries of their own version to the main build (Scala Native adds nativelib,
     javalib, scalalib, the compiler plugin, ... at `nativeVersion`): those come from the same source
     build, so the main build must resolve them from the isolated repository with the suffixed version;
   - large multi-module builds with cross Scala versions (Scala Native publishes per Scala version):
     build time, choosing what to publish (`.args`, a task other than `publish`).
