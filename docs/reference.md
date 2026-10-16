# Reference

## Declaring dependencies: `SourceDep`

`SourceDep`, `BuildTool` and every key below are imported automatically in `.sbt` files.

### Constructors

| Constructor | Description |
| --- | --- |
| `SourceDep.git(url: String, ref: String = "HEAD")` | A git repository. `ref` is a tag, branch, commit (full or abbreviated) or `HEAD`, the remote's default branch. |
| `SourceDep.local(dir: File)` | A directory on disk, used in place. |

### Methods

Every method returns a new `SourceDep`, so calls can be chained.

| Method | Description |
| --- | --- |
| `.subdir(path: String)` | Build the project in this subdirectory, relative to the repository or directory root. |
| `.modules(names: String*)` | Add only these published modules. Names are artifact ids, with or without the Scala suffix (`core` matches `core_3`). When the build publishes a module for several Scala versions (`core_2.13`, `core_3`), only the one matching the project's Scala binary version is added, with or without `.modules`. Other modules of the build are still forced (see [`sourceDepsForcedVersions`](#sourcedepsforcedversions)). |
| `.buildTool(tool: BuildTool)` | Skip detection. `BuildTool.Sbt`, `Mill`, `Gradle`, `Maven` or `ScalaCli`. |
| `.args(arguments: String*)` | Extra command-line arguments for this dependency's build tool, placed before the publishing task: `"-Pfast"` (Maven), `"-Pkey=value"` (Gradle), `"-J-Xmx4g"` or `"-Dprop=x"` (sbt). Calls add up. Part of the fingerprint. |
| `.env(variables: (String, String)*)` | Environment variables for this dependency's build only, over `sourceDepsEnv`. Calls add up. Part of the fingerprint. |
| `.javaHome(home: File)` | Build this dependency with another JDK. Same as `.env("JAVA_HOME" -> home.getAbsolutePath)`. Ignored by Mill (see [Mill](build-tools.md#mill)). |
| `.partialClone` | Git only: clone with `--filter=blob:none`. File contents are downloaded on checkout, which is much faster for large repositories with a long history. Checking out another ref then needs the network. Servers without partial clone support get a full clone. |
| `.named(name: String)` | Name of the dependency's directory under `sourceDepsDirectory`, replacing the computed `<name>-<hash>`. |

### Identity

Each dependency has an id used as its directory name: `<name>-<hash>`.

- `name` is the repository name without `.git` for a git dependency, or the directory name for a
  local one.
- `hash` is derived from the URL and ref for git (or the canonical path for local), plus the
  subdirectory.

Two declarations with the same location, ref and subdirectory share their directory, clone and
builds. `.named(...)` replaces the id. Use it only when you want a stable, readable directory name, and
make sure it is unique.

## Settings

| Setting | Scope | Default | Description |
| --- | --- | --- | --- |
| `sourceDependencies` | project | `Nil` | The project's source dependencies. |
| `sourceDepsDirectory` | build | `sourceDepsSharedDirectory`, or `<root>/.source-deps` when `sourceDepsShared` is `false` | Clones, work files and the isolated repository. |
| `sourceDepsShared` | build | `true` (`-Dsbt.source-deps.shared` / `SBT_SOURCE_DEPS_SHARED`) | Use the cache shared by every project of the machine. See [the shared cache](usage.md#the-shared-cache). |
| `sourceDepsSharedDirectory` | build | `~/.cache/sbt-source-deps` (per OS; `-Dsbt.source-deps.cache` / `SBT_SOURCE_DEPS_CACHE`) | Location of the shared cache. |
| `sourceDepsCommands` | build | see below | Command used to launch each tool when the dependency has no wrapper script. |
| `sourceDepsShowOutput` | build | `false` | Log the output of the dependency builds at info level (it is at debug level otherwise). |
| `sourceDepsEnv` | build | `Map.empty` | Extra environment variables for the build tools (not for git). Part of the fingerprint. `JAVA_HOME` also goes first on the `PATH`, and to sbt as `--java-home`. |

Default `sourceDepsCommands`:

```scala
Map(
  BuildTool.Sbt      -> Seq("sbt"),
  BuildTool.Mill     -> Seq("mill"),
  BuildTool.Gradle   -> Seq("gradle"),
  BuildTool.Maven    -> Seq("mvn"),
  BuildTool.ScalaCli -> Seq("scala-cli") // falls back to `scala` when scala-cli is not on the PATH
)
```

Build-scoped settings are set with `ThisBuild / ...`. Values can include extra arguments, for example
`Seq("sbt", "-J-Xmx4g")`. The plugin appends its own arguments.

## Tasks

| Task | Description |
| --- | --- |
| `sourceDepsResolve` | Builds stale dependencies and returns the modules added to the project (`Seq[ModuleID]`). Runs as part of `update`. |
| `sourceDepsForcedVersions` | Every module published by the project's source dependencies, at its source version. |
| `sourceDepsUpdate` | Fetches git dependencies (moving branch refs to their latest commit) and rebuilds what changed. |
| `sourceDepsStatus` | Logs `up to date`, `stale (will rebuild)` or `not checked out` for each dependency, without building. |
| `sourceDepsClean` | Deletes each dependency's directory (clone included) and its published artifacts. |

These tasks also cover the `_checkouts` entries built with their own tool (non-sbt 2 builds).

### `sourceDepsForcedVersions`

These versions are passed to coursier as forced versions (`csrConfiguration`'s `forceVersions`).
Wherever a forced module appears in the dependency graph, it resolves to the source build.

## Keys the plugin changes

| Key | Change |
| --- | --- |
| `resolvers` | Adds the Maven repository `source-deps` at `<sourceDepsDirectory>/repo` when the project has source dependencies. |
| `allDependencies` | Appends `sourceDepsResolve`. |
| `csrConfiguration` | Appends `sourceDepsForcedVersions` and the `_checkouts` modules to the forced versions. |
| `allowMismatchScala` | `true` when the project uses a 2.13-only checkout project from Scala 3. |
| `allDependencies` | Without the declarations a checkout replaces. |
| `excludeDependencies` | The declared modules whose artifact name differs from the checkout project's. |
| `scalacOptions` | `-Xplugin:<jar>` for the compiler plugins a checkout replaces. |
| `update` | Warns about published modules that an unused checkout project builds (reached through another library). |
| `csrInterProjectDependencies` | The coursier projects of the checkout projects (platform suffixes). |
| `Global / buildDependencies` | Adds the edges from your projects to the sbt 2 checkout projects. |
| `Global / onLoad`, `Global / onUnload` | Align the Scala version of the checkout projects; log the checkouts in use. |
| `extraProjects` | The hidden `sourceDepsCheckouts` project, when `_checkouts/` holds an sbt 2 build. |

These tasks are **uncached** (`Def.uncached`), so sbt 2's action cache never replays them. The
up-to-date check runs on every `update`. When nothing changed, its cost is hashing the dependencies'
files.

## `_checkouts`

| Item | Description |
| --- | --- |
| `<build root>/_checkouts/<name>/` | A build (or a symlink to one). sbt 2 builds are loaded with the consumer's build; others are built like `SourceDep.local` and their modules forced. See [usage](usage.md#editing-a-library-in-place-_checkouts). |
| `-Dsbt.source-deps.checkouts=<dir>`, `SBT_SOURCE_DEPS_CHECKOUTS=<dir>` | Another directory, absolute or relative to the build root. |
| `-Dsbt.source-deps.checkouts.meta=build`, `SBT_SOURCE_DEPS_CHECKOUTS_META=build` | Load sbt 2 checkouts into the meta-build too, instead of building them apart (`published`, the default). See [plugins](plugins.md#how-the-meta-build-uses-the-checkout). |
| Project `sourceDepsCheckouts` | Added to the build (hidden, `publish / skip`) when `_checkouts/` holds a build: it makes sbt load the checkout builds. |
| `allowMismatchScala` | Set to `true` on projects that use a 2.13-only checkout project from Scala 3. |
| `csrConfiguration` | Also forces the versions of the checkout projects' modules. |

## Directory layout

```
~/.cache/sbt-source-deps/                 sourceDepsDirectory (or <root>/.source-deps)
├── repo/                                 isolated Maven repository (shared by all dependencies)
│   ├── .index/+src.6bd572a241a1          modules of each published version, written last
│   ├── .lock                             file lock for writes to the repository
│   └── example/greeter_3/1.0.0+src.6bd572a241a1/
│       ├── greeter_3-1.0.0+src.6bd572a241a1.pom
│       ├── greeter_3-1.0.0+src.6bd572a241a1.jar
│       └── ...
└── greeter-1a2b3c4d/                     one directory per dependency (its id)
    ├── src/                              git clone (git dependencies only)
    ├── state-default                     last published suffix, per slot (see below)
    ├── staging/                          publication with the original versions
    ├── nested-scala-3/                   source dependencies of the dependency's own build (sbt only)
    ├── file-hashes.tsv                   per-file hashes reused while size and mtime are unchanged
    ├── sbt-global-settings/              generated global settings (sbt only)
    ├── source-deps.init.gradle           generated init script (Gradle only)
    └── .lock                             file lock serializing work on this dependency
```

A slot is the variant (`default`, or `scala-3` for sbt builds) plus, when the dependency has extra
environment variables or arguments, a hash of them: `state-scala-3-1f2e3d4c`. Only the latest build of
each slot is kept in `repo/`; older versions are deleted after a successful build. Slots no build has
used for 30 days are deleted too.

## Versions

A module published with version `V` by the dependency's build is added as `V+src.<fingerprint>`,
where the fingerprint is the first 12 hex digits of the source hash. For example, `1.0.0` becomes
`1.0.0+src.6bd572a241a1`.

- The same sources always give the same version, so a rebuild is skipped when it would produce the
  same thing.
- Different sources always give a different version, so caches (coursier, sbt's `update`, zinc,
  IDEs) never mix two builds.
- The original version stays visible, which helps when reading dependency trees.

See [how it works](how-it-works.md#versions) for details.
