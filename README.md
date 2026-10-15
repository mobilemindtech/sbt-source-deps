# sbt-source-deps

An sbt 2 plugin to depend on projects **built from source**, from a git repository or a local
directory, whatever their build tool: sbt, Mill, Gradle, Maven or scala-cli.

The dependency is built with its own tool, published into an isolated Maven repository under
`.source-deps/`, and added to your project like any other library. Its transitive dependencies
come from the published POMs. When the sources change, the next `update`/`compile` rebuilds it.

## Usage

```scala
// project/plugins.sbt
addSbtPlugin("br.com.mobilemind" % "sbt-source-deps" % "<version>")
```

```scala
// build.sbt
sourceDependencies ++= Seq(
  SourceDep.git("https://github.com/org/lib.git", ref = "v1.2.0"), // tag, branch or commit
  SourceDep.git("git@github.com:org/mono.git", ref = "main")
    .subdir("libs/json")                                          // project inside the repository
    .modules("json-core"),                                        // only these artifacts (with or without _3/_2.13)
  SourceDep.local(file("../my-lib"))                              // a directory on disk
    .buildTool(BuildTool.Gradle)                                  // skip detection
)
```

### Tasks and settings

| Key | Description |
| --- | --- |
| `sourceDependencies` | The source dependencies of the project. |
| `sourceDepsUpdate` | Fetches git dependencies (branches move only here) and rebuilds what changed. |
| `sourceDepsStatus` | Shows whether each dependency is up to date. |
| `sourceDepsClean` | Deletes the clones and published artifacts. |
| `sourceDepsDirectory` | Where clones and the repository live. Default: `<root>/.source-deps`. |
| `sourceDepsCommands` | Command per tool when the project has no wrapper, e.g. `sourceDepsCommands += BuildTool.Sbt -> Seq("/opt/sbt/bin/sbt")`. |
| `sourceDepsEnv` | Extra environment for the builds, e.g. `Map("JAVA_HOME" -> "/usr/lib/jvm/java-17")`. |

Add `.source-deps/` to your `.gitignore`.

## How it works

1. **Sources.** Git dependencies are cloned into `.source-deps/<name>-<hash>/src` and checked out at
   `ref`. Nothing is fetched until `sourceDepsUpdate` runs (or the ref is not known locally yet), so
   builds stay reproducible. Local directories are used in place.
2. **Fingerprint.** A SHA-256 over the content of the project files (`git ls-files` when available,
   ignoring `target/`, `build/`, `out/`, `.bsp/`, ...). Each fingerprint maps to a version suffix,
   `1.0.0` becoming `1.0.0+src.<hash>`. If that version is already in the repository nothing is built.
3. **Detection** (first match): `build.mill`/`build.sc` → Mill, `build.sbt` → sbt,
   `build.gradle(.kts)`/`settings.gradle(.kts)` → Gradle, `pom.xml` → Maven, `project.scala` →
   scala-cli. Wrappers (`./gradlew`, `./mvnw`, `./mill`) are preferred.
4. **Build and publish.**
   - sbt: `publish` with a generated global settings file that appends the suffix to each project's
     version, sets `publishTo` and picks the entry of `crossScalaVersions` matching your Scala binary
     version. Your global `*.sbt` files are kept.
   - Maven (`deploy -DaltDeploymentRepository`), Gradle (init script adding `maven-publish`), Mill
     (`__.publishM2Local`) and scala-cli (`publish --publish-repository`) publish with their own version
     into a staging repository. The artifacts are then copied with the suffixed version, and their POMs
     are rewritten so parents and sibling modules point at the suffixed versions too.
5. **Integration.** The repository is added to `resolvers`, the modules to `allDependencies`, and every
   published module is forced in coursier. `1.0.0+src.x` would otherwise tie with `1.0.0` and lose
   to a transitive `1.0.1`.

The up-to-date check runs on every `update`, never replayed from sbt 2's cache. Builds of one
dependency are serialized with a file lock, so several sbt sessions or Metals can share the
directory.

## Development

```
sbt scripted
```

The scripted tests build real projects with each tool (`src/sbt-test/source-deps/`). They require
`git`, `mvn`, `gradle` and scala-cli (or Scala ≥ 3.5's `scala` command) on the `PATH`. The Mill test
uses the project's `./mill` bootstrap script and downloads Mill and a JDK on first run.
