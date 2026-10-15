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
| `.modules(names: String*)` | Add only these published modules. Names are artifact ids, with or without the Scala suffix (`core` matches `core_3`). Other modules of the build are still forced (see [`sourceDepsForcedVersions`](#sourcedepsforcedversions)). |
| `.buildTool(tool: BuildTool)` | Skip detection. `BuildTool.Sbt`, `Mill`, `Gradle`, `Maven` or `ScalaCli`. |
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
| `sourceDepsDirectory` | build | `<root>/.source-deps` | Clones, work files and the isolated repository. |
| `sourceDepsCommands` | build | see below | Command used to launch each tool when the dependency has no wrapper script. |
| `sourceDepsEnv` | build | `Map.empty` | Extra environment variables for the build tools (not for git). |

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

### `sourceDepsForcedVersions`

These versions are passed to coursier as forced versions (`csrConfiguration`'s `forceVersions`).
Wherever a forced module appears in the dependency graph, it resolves to the source build.

## Keys the plugin changes

| Key | Change |
| --- | --- |
| `resolvers` | Adds the Maven repository `source-deps` at `<sourceDepsDirectory>/repo` when the project has source dependencies. |
| `allDependencies` | Appends `sourceDepsResolve`. |
| `csrConfiguration` | Appends `sourceDepsForcedVersions` to the forced versions. |

These tasks are **uncached** (`Def.uncached`), so sbt 2's action cache never replays them. The
up-to-date check runs on every `update`. When nothing changed, its cost is hashing the dependencies'
files.

## Directory layout

```
.source-deps/
├── repo/                                 isolated Maven repository (shared by all dependencies)
│   └── example/greeter_3/1.0.0+src.6bd572a241a1/
│       ├── greeter_3-1.0.0+src.6bd572a241a1.pom
│       ├── greeter_3-1.0.0+src.6bd572a241a1.jar
│       └── ...
└── greeter-1a2b3c4d/                     one directory per dependency (its id)
    ├── src/                              git clone (git dependencies only)
    ├── state-default                     last published suffix, per variant (state-scala-3 for sbt)
    ├── staging/                          publication with the original versions (non-sbt tools)
    ├── sbt-global-settings/              generated global settings (sbt only)
    ├── source-deps.init.gradle           generated init script (Gradle only)
    └── .lock                             file lock serializing work on this dependency
```

Only the latest build of each dependency (and variant) is kept in `repo/`. Older versions are
deleted after a successful build.

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
