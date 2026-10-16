# Troubleshooting

## Seeing what the dependency build did

The build tool's output is logged at debug level:

```
sbt> last sourceDepsResolved
sbt> sourceDepsUpdate
sbt> last sourceDepsUpdate
```

To see it as the build runs, for long builds or in CI:

```scala
ThisBuild / sourceDepsShowOutput := true
```

Each build ends with a line giving its duration:
`[source-deps] built ../greeter in 42 s: 2 module(s), version suffix +src.6bd572a241a1`.

When a build fails, its last 80 lines are already printed as errors, prefixed with the dependency's
id (`[greeter-1a2b3c4d] ...`).

To reproduce a failure by hand, run the logged command in the dependency's directory. The plugin
logs it before each build:

```
[info] [source-deps] greeter-1a2b3c4d: mvn --batch-mode ... deploy
```

## `could not detect the build tool`

None of the [detection files](build-tools.md#detection) is in the project directory. Check the
path, and `.subdir(...)` for projects inside a repository, or set `.buildTool(...)`.

## `could not run <tool> to build ...`

The tool is not on the `PATH` seen by sbt. The message suggests the fix. Install the tool, add a
wrapper to the dependency, or point the plugin at it:

```scala
ThisBuild / sourceDepsCommands += BuildTool.Gradle -> Seq("/opt/gradle/bin/gradle")
```

IDEs started from a desktop launcher often have a shorter `PATH` than your terminal.

## `the build of ... did not publish any module`

The build succeeded but nothing reached the staging repository. The message ends with a hint for the
tool. Check that:

- **sbt**: the projects are not `publish / skip := true`;
- **Maven**: `maven.deploy.skip` is not set, and no plugin replaces `deploy` (for example
  `nexus-staging-maven-plugin` with `skipLocalStaging=false`);
- **Gradle**: the projects apply the `java` plugin (Android and Kotlin Multiplatform are not
  supported);
- **Mill**: the modules extend `PublishModule`;
- **scala-cli**: `project.scala` has the `publish.*` directives.

## `modules X not published by ...; available: ...`

A name in `.modules(...)` does not match any published artifact. Use one of the listed names, with or
without the Scala suffix.

## `git ref 'X' not found`

The ref does not exist in the remote, even after a fetch. Check the tag or branch name. For commits,
use a hash that exists in the remote.

## Git asks for a password, or hangs

Interactive prompts are not supported: git runs with `GIT_TERMINAL_PROMPT=0` and SSH in batch mode,
so it fails with an authentication error instead of asking. Make sure `git clone <url>` works without
asking anything, using an SSH agent or a credential helper. See
[private repositories](usage.md#private-repositories).

## A branch does not pick up new commits

This is by design: branches only move on `sourceDepsUpdate`. See
[git repositories](usage.md#git-repositories).

## `cannot move the clone of ... it has local changes`

Tracked files in `.source-deps/<id>/src` were edited, and the plugin needs to check out another
commit (after `sourceDepsUpdate` or a ref change). The message lists the edited files. Commit or
discard them (`git -C <dir> checkout -- .`), or run `sourceDepsClean` to start over.

## The dependency is rebuilt every time

Something in the project directory changes on each build. Typical causes are generated files
written outside the [skipped directories](how-it-works.md#fingerprint) and not ignored by
`.gitignore`. Add them to the dependency's `.gitignore`. Inside a git work tree, ignored files are
not hashed.

## The source build is not the version used

Run `show sourceDepsForcedVersions` and check that the module is listed. Then run `evicted` or
`dependencyTree` to see what was resolved. Modules published by the source build are forced in
coursier, so a different version can only come from a different artifact id. For example, a Scala
2.13 artifact (`lib_2.13`) is not the same module as `lib_3`.

## Scala binary incompatibility

An sbt dependency is built with the entry of its `crossScalaVersions` matching your Scala version,
when there is one. Otherwise it keeps its own `scalaVersion`. Add your Scala version to the
dependency's `crossScalaVersions`, or use `.modules(...)` to choose the right artifact.

## The Gradle build fails on a recent JDK

The Gradle version of the dependency (often fixed by its wrapper) may not support the JDK sbt runs
on. Run its builds with another JDK:

```scala
ThisBuild / sourceDepsEnv := Map("JAVA_HOME" -> "/usr/lib/jvm/java-21")
```

## `_checkouts`: the checkout is not used

- Run `reload` after adding or removing a directory: checkouts are found while the build loads.
- `no project declares a module of <name>`: add the library to `libraryDependencies`, with the
  organization and name its build publishes (`show {file:/.../_checkouts/<name>/}<project>/projectID`).
  A module reached only through another library must be declared too.
- `ignoring <name>: no build detected`: the directory holds no build file the plugin knows (`build.sbt`,
  `pom.xml`, `build.gradle`, `build.mill`, `project.scala`, ...). A symlink must point at the build's
  root directory.

## `_checkouts`: `Scala version mismatch: app (Scala 3.x) depends on core (Scala 2.13.x)`

The checkout project could not be moved to your Scala version: its `crossScalaVersions` has no entry
with the same binary version. A project that sets `scalaVersion` itself gets
`crossScalaVersions := Seq(scalaVersion)` and does not inherit `ThisBuild / crossScalaVersions`: add
your version to the project's own `crossScalaVersions` in the checkout. (A 2.13-only project used
from Scala 3 is allowed and needs nothing.)

## `_checkouts`: the build fails to load

The checkout's plugins are loaded with yours. Two builds requiring incompatible versions of one
plugin may fail to load: align the versions, or build the library as a
[source dependency](usage.md#local-directories) instead (`SourceDep.local`), which runs it in its own
sbt.

## Starting over

```
sbt> sourceDepsClean
```

or delete the plugin's directory (`~/.cache/sbt-source-deps`, or `.source-deps/` when the shared cache
is off). Both are always safe: the next build clones and builds again. With the shared cache,
`sourceDepsClean` affects every project using those dependencies.
