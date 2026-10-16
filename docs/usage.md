# Usage and examples

All examples go in `build.sbt` unless stated otherwise. See the [reference](reference.md) for every
setting, task and method.

- [Local directories](#local-directories)
- [Git repositories](#git-repositories)
- [Private repositories](#private-repositories)
- [A project inside a repository (monorepos)](#a-project-inside-a-repository-monorepos)
- [Choosing modules of a multi-module build](#choosing-modules-of-a-multi-module-build)
- [Forcing the build tool](#forcing-the-build-tool)
- [Several dependencies](#several-dependencies)
- [Multi-project builds](#multi-project-builds)
- [Dependencies that use sbt-source-deps too](#dependencies-that-use-sbt-source-deps-too)
- [Editing a library in place (`_checkouts`)](#editing-a-library-in-place-_checkouts)
- [sbt plugins from source (Scala Native)](#sbt-plugins-from-source-scala-native)
- [Switching between a local checkout and git](#switching-between-a-local-checkout-and-git)
- [Java libraries (Maven, Gradle)](#java-libraries-maven-gradle)
- [Scala versions and cross-building](#scala-versions-and-cross-building)
- [Overriding a library you already depend on](#overriding-a-library-you-already-depend-on)
- [Custom tool commands and JDK](#custom-tool-commands-and-jdk)
- [The shared cache](#the-shared-cache)
- [Continuous integration](#continuous-integration)
- [IDEs (Metals, IntelliJ)](#ides-metals-intellij)
- [Everyday commands](#everyday-commands)

## Local directories

```scala
sourceDependencies += SourceDep.local(file("../greeter"))
```

The directory is used in place: the plugin never writes into it. The dependency's own build may
still write its usual output there (`target/`, `build/`, `out/`, ...).

Every compile hashes the directory's files. When anything changed (a source, a build file, a resource),
the dependency is rebuilt. If the directory is inside a git work tree, `.gitignore` is respected.
Otherwise build output directories are skipped (see [how it works](how-it-works.md#fingerprint)).

> Relative paths given to `file(...)` are resolved against the directory sbt was started in, which is
> normally the root of your build. To be explicit, use
> `(LocalRootProject / baseDirectory).value / ".." / "greeter"`.

## Git repositories

```scala
// a tag: never changes
sourceDependencies += SourceDep.git("https://github.com/example/greeter.git", ref = "v1.2.0")

// a commit: never changes
sourceDependencies += SourceDep.git("https://github.com/example/greeter.git", ref = "3f9c2a1")

// a branch: moves only when you run sourceDepsUpdate
sourceDependencies += SourceDep.git("https://github.com/example/greeter.git", ref = "main")

// the remote's default branch (ref defaults to "HEAD")
sourceDependencies += SourceDep.git("https://github.com/example/greeter.git")
```

Any URL that `git clone` accepts works: `https://`, `ssh://`, `git@host:org/repo.git`, `file:///path`,
and also `file:/path` as produced by `java.net.URI`.

The clone lives in `.source-deps/<name>-<hash>/src`. The plugin behaves as follows:

- On the first build it clones the repository and checks out `ref` (detached HEAD).
- Later builds **do not fetch**. A branch keeps pointing at the commit you already have.
- `sourceDepsUpdate` fetches every git dependency, moves branches to the remote's latest commit,
  and rebuilds what changed.
- If `ref` is unknown locally (you changed it in `build.sbt` to a newer tag), the plugin fetches once
  automatically.

Changing `ref` or the URL creates a different dependency directory (`<name>-<hash>` includes both), so
switching back is instant if the old one is still there.

You can also edit files in the clone, for a quick experiment. The edits are detected and trigger a
rebuild like a local directory would. Commit them elsewhere: the clone is managed by the plugin, and
`sourceDepsClean` deletes it. While tracked files have local edits, the plugin refuses to move the
clone to another commit (after a `sourceDepsUpdate` or a ref change), so a build never mixes your
edits with a different commit.

### Large repositories

For a repository with a long history, a partial clone downloads only the commits and trees, and the
file contents of the checked-out commit:

```scala
sourceDependencies += SourceDep.git("https://github.com/example/huge.git", ref = "v5.0.0").partialClone
```

Any ref can still be checked out later, but that needs the network to download the missing file
contents. Servers without partial clone support (old self-hosted ones) silently get a full clone.

## Private repositories

The plugin runs the `git` command, so it uses your usual git authentication: SSH agent, credential
helpers, `~/.netrc`, `GIT_SSH_COMMAND`, and so on.

```scala
sourceDependencies += SourceDep.git("git@github.com:my-company/internal-lib.git", ref = "v3.0.0")
```

Interactive prompts are not supported. Make sure `git clone <url>` works in a terminal without asking
for a password or a host key confirmation before declaring the dependency. The plugin runs git with
`GIT_TERMINAL_PROMPT=0` and, unless you set `GIT_SSH_COMMAND` yourself, with
`GIT_SSH_COMMAND="ssh -o BatchMode=yes"`, so a missing credential fails at once instead of hanging.

Build tools of the dependency resolve their own dependencies with their own configuration:
`~/.m2/settings.xml` for Maven, `~/.gradle/gradle.properties` for Gradle, and your global `*.sbt`
files for sbt (the plugin copies them for the build, see [build tools](build-tools.md#sbt)).

## A project inside a repository (monorepos)

```scala
sourceDependencies += SourceDep
  .git("https://github.com/example/platform.git", ref = "release-5")
  .subdir("libs/json")
```

The repository is cloned whole. The build and the fingerprint only cover `libs/json`, so changes
elsewhere in the repository do not trigger rebuilds. `subdir` works with local directories too:

```scala
sourceDependencies += SourceDep.local(file("../platform")).subdir("libs/json")
```

Two subdirectories of the same repository are two dependencies, each with its own clone:

```scala
val platform = "https://github.com/example/platform.git"
sourceDependencies ++= Seq(
  SourceDep.git(platform, "release-5").subdir("libs/json"),
  SourceDep.git(platform, "release-5").subdir("libs/http")
)
```

## Choosing modules of a multi-module build

By default every module the dependency publishes is added to your project, except POM-only modules
such as Maven parents. To use only some of them:

```scala
sourceDependencies += SourceDep
  .git("https://github.com/example/toolkit.git", ref = "v2.0.0")
  .modules("toolkit-core", "toolkit-json")
```

Names are artifact ids, with or without the Scala suffix: `"core"` matches `core_3`, `core_2.13` and
`core`. A name that the build did not publish fails with the list of available modules:

```
modules toolkit-jsn not published by https://github.com/example/toolkit.git@v2.0.0;
available: toolkit-core_3, toolkit-json_3, toolkit-http_3
```

Dependencies between modules still work. If `toolkit-json` depends on `toolkit-util`,
`toolkit-util` comes transitively, built from the same sources.

## Forcing the build tool

Detection looks at the files in the project's root directory (see [build tools](build-tools.md#detection)).
When a project ships files for several tools, for example a `pom.xml` kept for other users of a
Gradle project, choose explicitly:

```scala
sourceDependencies += SourceDep.local(file("../legacy-lib")).buildTool(BuildTool.Maven)
```

The values are `BuildTool.Sbt`, `BuildTool.Mill`, `BuildTool.Gradle`, `BuildTool.Maven` and
`BuildTool.ScalaCli`.

## Several dependencies

```scala
sourceDependencies ++= Seq(
  SourceDep.git("https://github.com/example/json.git", ref = "v2.1.0"),
  SourceDep.git("git@github.com:my-company/billing.git", ref = "main").modules("billing-api"),
  SourceDep.local(file("../greeter")),
  SourceDep.local(file("../legacy")).buildTool(BuildTool.Maven)
)
```

Dependencies are resolved one after another, and each is only rebuilt when its own sources (or its
[own source dependencies](#dependencies-that-use-sbt-source-deps-too)) changed.

## Multi-project builds

`sourceDependencies` is a per-project setting, like `libraryDependencies`. Declare it on each
project that needs it:

```scala
val greeter = SourceDep.local(file("../greeter"))

lazy val core = project
  .settings(sourceDependencies += greeter)

lazy val app = project
  .dependsOn(core) // gets greeter transitively through core
```

> `ThisBuild / sourceDependencies` has no effect: the plugin sets the default (`Nil`) on each project,
> which takes precedence. Share a `val` instead.
>
> In sbt 2, a setting written at the top level of `build.sbt` (outside `.settings(...)`) applies to
> every subproject. A top-level `sourceDependencies += greeter` therefore adds the dependency to all
> of them, which is fine when that is what you want.

Projects on different Scala versions can share an sbt dependency: each Scala binary version gets its
own build of it (`greeter_2.13` and `greeter_3`), as long as the dependency cross-builds for both.

Projects declaring the same dependency share one clone and one build, because the working
directory belongs to the whole build. Concurrent resolutions from parallel tasks wait for each
other.

## Dependencies that use sbt-source-deps too

A dependency built with sbt can declare its own source dependencies. Nothing special is needed on
either side:

```scala
// middle/build.sbt (the dependency)
sourceDependencies += SourceDep.local(file("../greeter"))

// app/build.sbt (your project)
sourceDependencies += SourceDep.local(file("../middle"))
```

When `app` builds `middle`, `middle`'s own source dependencies are built into **`app`'s**
`.source-deps` (not `middle/.source-deps`), so `app` resolves `greeter` through `middle`'s POM. Then:

- a change in `greeter` rebuilds `greeter` and `middle` on `app`'s next compile;
- `sourceDepsUpdate` in `app` also fetches `middle`'s git dependencies;
- `greeter`'s version is forced in `app` like `middle`'s;
- if `app` declares `greeter` too, both levels share one build of it;
- a cycle (`a` → `b` → `a`) fails with `cycle between source dependencies: ...`.

Requirements:

- the dependency must use a version of the plugin that supports this (0.1.0 or later) and must not
  set `sourceDepsDirectory` itself;
- `sourceDepsClean` in `app` cleans `middle`, not `greeter`; clean `greeter` by declaring it, or
  delete `.source-deps`.

See [how it works](how-it-works.md#dependencies-that-use-the-plugin) for the details.

## Editing a library in place (`_checkouts`)

To change a library and test it from your project at once, put its build in `_checkouts/` at the root
of your build, like rebar3's `_checkouts`. A symlink is the usual way:

```
app/
├── build.sbt              libraryDependencies += "example" %% "greeter" % "1.4.0"   (unchanged)
└── _checkouts/
    └── greeter -> ~/src/greeter
```

```
$ mkdir -p _checkouts && ln -s ~/src/greeter _checkouts/greeter
sbt> reload
[info] [source-deps] _checkouts: core (greeter, Scala 3.8.4) replaces its published module in app
```

How a checkout is used depends on its build:

| Checkout | How | Edit -> test |
| --- | --- | --- |
| sbt 2 build | Loaded **in your build**: its projects replace the modules | Incremental compilation, IDE on the working copy |
| sbt 1, Maven, Gradle, Mill, scala-cli | Built with its own tool and published to the isolated repository, like [`SourceDep.local`](#local-directories); its modules **replace** the declared ones, nothing is added | A full build of the checkout when its sources change |

For sbt 2 checkouts:

- The library's build is loaded **together with yours**. Each project of yours that declares a module
  produced by a checkout project (`example:greeter`, matched by organization and name) depends on that
  project instead. The released version is not downloaded.
- Edits in the checkout compile **incrementally** on your next `compile`/`run`/`test`, with errors
  reported as usual. Nothing is published, and its classes go to your build's `target/`.
- **IDEs** see the checkout projects as modules of the build (BSP targets for Metals, sbt import for
  IntelliJ): navigation opens your working copy, and refactorings cross both.
- The checkout's own dependencies come along, and its version wins over any version of the same
  module that other libraries bring in.
- **Scala version**: a checkout project compiles with your project's Scala version when its
  `crossScalaVersions` has one with the same binary version (`3.8.4` for a `3.x` consumer); otherwise it
  keeps its own. A 2.13-only project can be used from Scala 3.
- To go back to the released version, remove the directory (or the symlink) and `reload`.

For other builds, every module the checkout publishes replaces the same module wherever it appears,
transitively included, through [forced versions](how-it-works.md#forced-versions). Unlike a
`sourceDependencies` entry, the checkout adds nothing: a module nobody depends on is just ignored.
Directories without a recognized build are skipped with a warning.

Limits (sbt 2 checkouts):

- A module reached **only transitively** (through another library) is replaced once you declare it in
  `libraryDependencies` too. Until then, `update` warns:
  `_checkouts: app gets the published example:greeter-extras_3 (through another library), not extras
  from greeter: add "example" %% "greeter-extras" to libraryDependencies to use the checkout`.
- A checkout project compiles with **one** Scala version: two projects of yours on different Scala
  versions using the same checkout get a warning, and only one of them can compile against it.
- The checkout's plugins are loaded with yours, and your auto plugins apply to its projects too.
  Conflicting plugin versions between both builds may fail to load.
- Once `_checkouts/` holds a build, sbt names your implicit root project `<directory>-root` (it does so
  whenever a build has more than one project). Builds that declare their root project keep its name.
- sbt writes the checkout's meta-build output (`project/target/`, `project/project/`) and, when the
  checkout uses this plugin too, an empty `target/source-deps-checkouts/` in the checkout, as it does
  for any build it loads. The checkout's classes go to your build's `target/`.
- The directory can be changed with `-Dsbt.source-deps.checkouts=<dir>` or
  `SBT_SOURCE_DEPS_CHECKOUTS=<dir>` (relative to the build root).

`_checkouts` and `sourceDependencies` can be combined: use `_checkouts` for the libraries you are
editing, and source dependencies for the rest.

## sbt plugins from source (Scala Native)

`_checkouts` also works for **sbt plugins**, through the meta-build (`project/`): the plugin, its
libraries and a compiler plugin can all come from one checkout. See
[sbt plugins from source](plugins.md), with Scala Native as the example
([`examples/scala-native`](../examples/scala-native)).

## Switching between a local checkout and git

A common workflow: use a released tag normally, and your local checkout while you work on both
projects at once.

```scala
val greeter =
  sys.env.get("GREETER_HOME") match
    case Some(dir) => SourceDep.local(file(dir))
    case None      => SourceDep.git("https://github.com/example/greeter.git", ref = "v1.4.0")

sourceDependencies += greeter
```

```
GREETER_HOME=../greeter sbt
```

You can also mix: keep the regular `libraryDependencies += "example" %% "greeter" % "1.4.0"`, and add
the source dependency only when the variable is set. The source build wins over the released version
(see [overriding](#overriding-a-library-you-already-depend-on)).

```scala
libraryDependencies += "example" %% "greeter" % "1.4.0"
sourceDependencies ++= sys.env.get("GREETER_HOME").map(d => SourceDep.local(file(d))).toSeq
```

## Java libraries (Maven, Gradle)

Java libraries work like any other dependency:

```scala
sourceDependencies += SourceDep.git("https://github.com/example/java-utils.git", ref = "2.3.1")
```

```scala
// calling Java code from Scala
val text = javautils.Strings.capitalize("hello")
```

Maven reactors (a parent POM with `<modules>`) are supported. The parent is published so that the
modules' POMs resolve, but it is not added to your dependencies. Gradle projects do not need any
publishing configuration: the plugin adds `maven-publish` for the build.

## Scala versions and cross-building

The artifacts the dependency publishes must be usable from your Scala version:

- **sbt dependencies** are switched, for the build, to the entry of their `crossScalaVersions` that
  matches your project's Scala binary version. With your project on Scala 3 and the library declaring
  `crossScalaVersions := Seq("2.13.16", "3.8.4")`, the library is built and published for `3.8.4`
  even if its default `scalaVersion` is 2.13. When no entry matches, the library's own `scalaVersion`
  is used.
- **Mill, scala-cli, Gradle and Maven** dependencies are built as their build defines. When a build
  publishes a module for several Scala versions (a Mill `Cross[...]` module gives `core_2.13` and
  `core_3`), each project of your build gets only the artifact of its own Scala binary version. A
  module without an artifact for that version is added as published (for example, a 2.13 library used
  from Scala 3).

Scala 3 projects can use Scala 2.13 artifacts (`lib_2.13`) as long as the library does not expose
Scala 2 macros. The plugin adds the exact artifact id, so no `CrossVersion.for3Use2_13` is needed.

If projects of your build use different Scala versions, an sbt dependency is built once per Scala
binary version. Those builds are kept side by side.

## Overriding a library you already depend on

A source dependency replaces any other version of the same module in the dependency graph, whether
declared directly or brought in transitively:

```scala
libraryDependencies += "example" %% "http-client" % "4.0.0"  // depends on example::json-core 2.5.0
sourceDependencies += SourceDep.local(file("../json")).modules("json-core")   // a patched json-core
```

Here `http-client` is resolved with your `json-core` built from `../json`, not with 2.5.0.

Every module published by the source build is forced to its source version, including modules you
did not select with `.modules(...)`. This is how you test a patched fork of a library deep in your
dependency tree.

## Custom tool commands and JDK

When the dependency has no wrapper script, the plugin runs `sbt`, `mill`, `gradle`, `mvn` or
`scala-cli` from the `PATH`. Wrapper scripts in the dependency (`./gradlew`, `./mvnw`, `./mill`) always
take precedence. To use another executable:

```scala
ThisBuild / sourceDepsCommands += BuildTool.Maven -> Seq("/opt/maven-3.9/bin/mvn")
ThisBuild / sourceDepsCommands += BuildTool.Sbt -> Seq("sbt", "-J-Xmx4g")
```

To run the builds with another JDK, or pass any environment variable:

```scala
ThisBuild / sourceDepsEnv := Map(
  "JAVA_HOME" -> "/usr/lib/jvm/java-17",
  "MAVEN_OPTS" -> "-Xmx2g"
)
```

The environment applies to the build tools only. Git commands run with sbt's environment.

- `JAVA_HOME` also puts `$JAVA_HOME/bin` first on the `PATH` of the builds, and sbt dependencies get
  `--java-home`: launch scripts (sbt's among them) run the `java` found on the `PATH` and would
  otherwise ignore it. A JVM pinned by the dependency's own build still wins: Gradle's
  `gradle/gradle-daemon-jvm.properties`, scala-cli's `//> using jvm`.
- **Mill ignores `JAVA_HOME`**: Mill 1.x runs on the JVM named by `mill-jvm-version` (in the
  `build.mill` header, `.mill-jvm-version` or `.config/mill-jvm-version`), or on a JDK it downloads
  when none is set. Only `mill-jvm-version: system` makes it use `JAVA_HOME`. The plugin never edits the
  dependency's files, so it only logs a warning when `JAVA_HOME` is set for a Mill dependency.
- The environment is part of the [fingerprint](how-it-works.md#fingerprint): changing it rebuilds the
  dependencies.

### Options for one dependency

Arguments, environment variables and the JDK can also be set per dependency. They add to the global
settings, and changing them rebuilds that dependency:

```scala
sourceDependencies ++= Seq(
  // a Maven profile and more memory
  SourceDep.local(file("../billing")).args("-Pfast").env("MAVEN_OPTS" -> "-Xmx2g"),
  // a Gradle property
  SourceDep.git("https://github.com/example/json.git", ref = "v2.1.0").args("-PskipChecks=true"),
  // an old library that only builds on JDK 11
  SourceDep.local(file("../legacy")).javaHome(file("/usr/lib/jvm/java-11"))
)
```

The arguments go before the publishing task of the tool (`deploy`, `publish`,
`publishAllPublicationsTo...`, `__.publishM2Local`).

## The shared cache

Clones, builds and the isolated repository live in a cache shared by every project of the machine:

| OS | Default location |
| --- | --- |
| Linux | `$XDG_CACHE_HOME/sbt-source-deps`, or `~/.cache/sbt-source-deps` |
| macOS | `~/Library/Caches/sbt-source-deps` |
| Windows | `%LOCALAPPDATA%\sbt-source-deps` |

Two projects using the same dependency (same git URL and ref, or same local directory) build it
once. Each build is identified by its sources and options, so sharing never mixes different builds:

- a project using other `.env`/`.args`, `sourceDepsEnv` or another Scala version gets its own version
  of the dependency, next to the others;
- `sourceDepsUpdate` in one project moves the shared clone of a branch: the other projects pick up the
  new commit on their next compile;
- `sourceDepsClean` removes the dependency for every project; the next build of each rebuilds it.

Versions built with options or a Scala version no build has used for 30 days are deleted at the next
build of that dependency. Deleting the cache directory is always safe.

To use one directory per build instead (`<build root>/.source-deps`), or another location:

```scala
ThisBuild / sourceDepsShared := false
ThisBuild / sourceDepsSharedDirectory := file("/var/cache/source-deps")
// or any directory, shared or not
ThisBuild / sourceDepsDirectory := file("/var/cache/my-build/source-deps")
```

Without editing the build, for example in CI:

| System property | Environment variable | Effect |
| --- | --- | --- |
| `-Dsbt.source-deps.shared=false` | `SBT_SOURCE_DEPS_SHARED=false` | Default of `sourceDepsShared` |
| `-Dsbt.source-deps.cache=<dir>` | `SBT_SOURCE_DEPS_CACHE=<dir>` | Default of `sourceDepsSharedDirectory` |

See the [directory layout](reference.md#directory-layout).

## Continuous integration

- **Pin refs.** Prefer tags or commits over branches in CI. A branch only moves on
  `sourceDepsUpdate`, but a fresh CI checkout clones the branch's current commit.
- **Install the tools.** The CI image needs `git` and the build tool of each dependency, unless the
  dependency ships a wrapper.
- **Cache** the plugin's directory between runs to skip clones and builds whose sources did not
  change. Point it inside the workspace with `SBT_SOURCE_DEPS_CACHE`, or disable sharing with
  `SBT_SOURCE_DEPS_SHARED=false` and cache `.source-deps/`. The fingerprint is based on content, so a
  restored cache is reused safely.
- **Warm up** with `sbt update` to build all source dependencies before the rest of the pipeline.

```yaml
# GitHub Actions
env:
  SBT_SOURCE_DEPS_CACHE: ${{ github.workspace }}/.source-deps
steps:
- uses: actions/cache@v4
  with:
    path: .source-deps
    key: source-deps-${{ hashFiles('build.sbt') }}
- run: sbt update test
```

## IDEs (Metals, IntelliJ)

For a library you are editing, prefer [`_checkouts`](#editing-a-library-in-place-_checkouts): the IDE
then works on your working copy. For source dependencies:

Importing the build (BSP or the sbt import) runs `update`, which builds stale source dependencies.
The first import can take a while, depending on the dependencies.

After editing a local dependency, re-import (or run `compile` in the sbt shell) to rebuild it.
The IDE sees the published jars and their `-sources.jar` when the tool publishes one, so navigation
into the dependency works. It opens the published sources, not your working copy.

## Everyday commands

| Command | What it does |
| --- | --- |
| `compile`, `update`, `test`, ... | Build the stale dependencies first, transparently |
| `sourceDepsStatus` | Show `up to date`, `stale (will rebuild)` or `not checked out` for each dependency |
| `sourceDepsUpdate` | Fetch git dependencies, move branches, rebuild what changed |
| `sourceDepsClean` | Delete clones and published artifacts; the next build starts from scratch |
| `show sourceDepsResolve` | List the modules added to the project |
| `show sourceDepsForcedVersions` | List every module forced to its source version |
| `reload` | Pick up a directory added to or removed from `_checkouts/` |
