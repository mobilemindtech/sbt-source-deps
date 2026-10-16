# sbt-source-deps

An sbt 2 plugin to depend on projects **built from source**, from a git repository or a local
directory, whatever their build tool: **sbt, Mill, Gradle, Maven or scala-cli**.

```scala
// build.sbt
sourceDependencies ++= Seq(
  SourceDep.git("https://github.com/org/json-lib.git", ref = "v2.1.0"),
  SourceDep.local(file("../my-lib"))
)
```

On the next `compile`, each dependency is checked out, built with its own tool, published into an
isolated Maven repository in a cache shared by your projects (`~/.cache/sbt-source-deps`), and added to your project like any other library,
transitive dependencies included. When its sources change, the next `compile` rebuilds it. When they
don't, nothing is built.

## Features

- **Git or local directory** sources. Git refs can be a tag, branch or commit, and a project can live
  in a subdirectory of the repository.
- **Build tool detection** (sbt, Mill, Gradle, Maven, scala-cli). Project wrappers (`./gradlew`,
  `./mvnw`, `./mill`) are preferred.
- **Rebuild only on change.** The trigger is a content hash of the dependency's sources.
- **Reproducible git builds.** Branches only move when you run `sourceDepsUpdate`.
- **Isolated.** `~/.m2` and `~/.ivy2` are never touched, and your local directories are never modified.
- **The source build always wins** over other versions of the same module brought in transitively.
- **Scala cross-versions.** For sbt dependencies, the plugin picks the `crossScalaVersions` entry that
  matches your project's Scala version.
- **`_checkouts` for sbt 2 libraries.** Put (or symlink) a library's build in `_checkouts/`: it
  replaces the published module you declare, compiles incrementally with your project and shows up in
  the IDE. Nothing is published, and `build.sbt` does not change.
- **Safe for concurrent sessions.** sbt, Metals and other sessions can share the directory, because
  builds are serialized with a file lock.

## Documentation

| Document | Contents |
| --- | --- |
| [Getting started](docs/getting-started.md) | Installation and a first source dependency, step by step |
| [Usage and examples](docs/usage.md) | Recipes for git, local directories, monorepos, multi-project builds, CI, ... |
| [Reference](docs/reference.md) | Every setting, task and `SourceDep` method; directory layout |
| [Build tools](docs/build-tools.md) | What each supported tool needs, and how it is invoked |
| [How it works](docs/how-it-works.md) | Fingerprints, versioning, POM rewriting, forced versions, locking |
| [Troubleshooting](docs/troubleshooting.md) | Common problems and how to solve them |
| [Development](docs/development.md) | Building and testing the plugin, design decisions, status and roadmap |

## Requirements

- sbt 2.x (tested with 2.0.10)
- `git` on the `PATH` for git dependencies
- The build tool of each dependency (or its wrapper script in the dependency's repository)

## Status

Pre-release (`0.1.0-SNAPSHOT`), not published yet. See [Development](docs/development.md#status) for
what is tested and what is planned.
