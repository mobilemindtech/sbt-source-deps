# Development

## Building

```
sbt compile
sbt publishLocal        # br.com.mobilemind:sbt-source-deps:0.1.0-SNAPSHOT (sbt 2)
sbt scripted            # all integration tests
sbt "scripted source-deps/local-maven"
```

The plugin targets sbt 2.0.x and is compiled with Scala 3.8.4, the version sbt 2.0.10 runs on.

Scaladoc is disabled (`Compile / doc / sources := Nil`), because Scaladoc cannot read
`lmcoursier`'s `@data` classes used to force versions. This must be solved before publishing to Maven
Central, which requires a Javadoc jar. An empty jar is enough.

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
| `env-sbt` | `sourceDepsEnv` reaches the build (a source generator reads it); changing it rebuilds |
| `build-errors` | Message when the tool command does not exist, and when an sbt build publishes nothing (`publish / skip`) |
| `local-maven` | Reactor with parent; consumer selects `greeter` only; the change is in `greeter-api`, which comes transitively |
| `local-gradle` | Plain `java-library` without publishing configuration |
| `local-mill` | Mill 1.3.0 through the project's `./mill` bootstrap script |
| `local-scalacli` | `project.scala` with `publish.*` directives; falls back to the `scala` runner |

Requirements: `git`, `mvn`, `gradle` and scala-cli (or `scala` ≥ 3.5) on the `PATH`. The Mill test
downloads Mill and a JDK on the first run. A full `scripted` run takes about 10 minutes.

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

### Scaladoc and `lmcoursier`

`publishLocal` of the plugin failed in `Compile / doc` ("undefined: new dataclass.data"). The
plugin's docs are disabled for now.

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
- Roadmap item 1 (tests): Gradle Kotlin DSL + `scala` plugin; `sourceDepsEnv` in the fingerprint;
  `JAVA_HOME` honoured by sbt and on the `PATH`.

**Verified** with sbt 2.0.10 on JDK 27 (Linux), by the sixteen scripted tests above (the sbt 1.x
dependency with sbt 1.13.0).

**Not verified yet**
- Gradle builds publishing several Scala versions through variants (not supported);
- `JAVA_HOME` with Maven, Gradle, Mill and scala-cli (checked by hand with sbt only);
- macOS and Windows (paths, wrapper scripts, `sh` fallback).

## Roadmap

Next steps, roughly in priority order:

1. **Tests for what is not verified yet** (list above).
2. **Progress feedback**: log the elapsed time of each build; optionally stream the tool output at
   info level (setting).
3. **Per-dependency options**: extra arguments and environment per `SourceDep` (e.g. Maven profiles,
   Gradle properties, a specific JDK).
4. **Performance**: cache file hashes by (path, size, mtime) to avoid reading every file on each
   `update` for large dependencies.
5. **Publishing the plugin**: fix Scaladoc (or publish an empty Javadoc jar), POM metadata, CI
   (GitHub Actions running `scripted`), release to Maven Central.
6. **Optional**: `ProjectRef` mode for sbt 2 dependencies (same build, incremental compilation, IDE
   navigation into sources), and a global shared cache directory.
