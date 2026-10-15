# Build tools

- [Detection](#detection)
- [sbt](#sbt)
- [Maven](#maven)
- [Gradle](#gradle)
- [Mill](#mill)
- [scala-cli](#scala-cli)
- [Tested versions](#tested-versions)

## Detection

The project directory (the repository root, or `.subdir(...)`) is inspected in this order. The first
match wins:

| Order | Files | Tool |
| --- | --- | --- |
| 1 | `build.mill`, `build.mill.scala`, `build.mill.yaml`, `build.sc` | Mill |
| 2 | `build.sbt`, `project/build.properties` | sbt |
| 3 | `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts` | Gradle |
| 4 | `pom.xml` | Maven |
| 5 | `project.scala` | scala-cli |

Mill and sbt come first because Scala projects sometimes keep a `pom.xml` or Gradle files for other
consumers. Override detection with `.buildTool(...)`.

If nothing matches, resolution fails with:

```
could not detect the build tool of ../lib in /path/to/lib (use .buildTool(...))
```

## Launch command

For each tool, the plugin runs, in order of preference:

1. the project's wrapper script, if present: `./mvnw`, `./gradlew` or `./mill` (run with `sh` when it is
   not executable);
2. otherwise `sourceDepsCommands(tool)`, by default the tool's name on the `PATH`.

sbt and scala-cli have no wrapper.

The output of the tool is logged at debug level. Show it with `last sourceDepsResolved` after a
compile, or `last sourceDepsUpdate` after an update.
When the build fails, its last 80 lines are logged as errors.

## sbt

**Command**

```
sbt --server --batch --no-colors --supershell=never -Dsbt.global.settings=<dir> publish
```

**How the version is changed.** `set every version ~= ...` creates cyclic references, and
`set every version := ...` would give every project the same version. Instead, the plugin generates
a global settings directory with an `onLoad` hook that runs once the build is loaded. For each
project the hook:

- appends the suffix to its version;
- switches `scalaVersion` to the `crossScalaVersions` entry matching your Scala binary version, when
  there is one;
- sets `publishTo` to the isolated repository and `publishMavenStyle := true`;
- disables the Scaladoc jar (`Compile / packageDoc / publishArtifact := false`) to save time.

**Your global settings are kept.** Because `-Dsbt.global.settings` replaces your global settings
directory, the plugin copies your `*.sbt` files from `~/.sbt/1.0` (sbt 1.x projects) or `~/.sbt/2`
(sbt 2.x) into the generated one. Credentials and resolvers defined there keep working. Global
plugins (`~/.sbt/<series>/plugins`) are untouched.

**Requirements**

- The generated file is written to compile on both sbt 1.x and 2.x. So far only sbt 2.0.10
  dependencies are tested.
- Projects with `publish / skip := true` are not published, as usual.
- A build that publishes nothing fails with `the build of ... did not publish any module`.

**Example**

```scala
// greeter/build.sbt
organization := "example"
name := "greeter"
version := "1.0.0"
scalaVersion := "2.13.16"
crossScalaVersions := Seq("2.13.16", "3.8.4")
```

Used from a Scala 3 project, this library is published as `example:greeter_3:1.0.0+src.<hash>`.

## Maven

**Command**

```
mvn --batch-mode --no-transfer-progress -DskipTests -Dmaven.javadoc.skip=true -Dgpg.skip=true \
    -DaltDeploymentRepository=source-deps::default::file:///.../staging deploy
```

- `deploy` runs the full lifecycle up to `deploy`, except tests, Javadoc and signing.
- The alternative repository uses the legacy `id::layout::url` syntax. Recent versions of
  `maven-deploy-plugin` print a warning about it, but it is the only syntax that version 2.x of the
  plugin understands.
- Reactors (parent POM + `<modules>`) are supported. Parents with `<packaging>pom</packaging>` are
  published so that the modules resolve, but are not added to your dependencies.
- Modules that skip deployment (`maven.deploy.skip`) are not published.
- CI-friendly versions (`${revision}`) are handled: coordinates are read from the repository layout,
  and parent references using a placeholder are rewritten. This case is not covered by a test yet.

**Requirements.** Maven 3.x or `./mvnw`. Repositories and credentials come from your
`~/.m2/settings.xml`.

**Example**

```xml
<!-- greeter/pom.xml -->
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>greeter-parent</artifactId>
  <version>1.0.0</version>
  <packaging>pom</packaging>
  <modules>
    <module>api</module>
    <module>core</module>
  </modules>
</project>
```

```scala
sourceDependencies += SourceDep.local(file("../greeter")).modules("greeter")
// greeter-api comes transitively, built from the same sources
```

## Gradle

**Command**

```
gradle --init-script <workdir>/source-deps.init.gradle --console=plain \
       publishAllPublicationsToSourceDepsStagingRepository
```

The generated init script does the following for every project with the `java` plugin, which includes
`java-library`, `application` and Kotlin JVM:

- applies `maven-publish`;
- sets `group` to the root project's name when it is empty;
- creates a publication from `components.java` when the project declares no `MavenPublication` (existing
  publications are used as they are);
- adds the `sourceDepsStaging` repository.

**Requirements**

- Gradle or `./gradlew`. The Gradle version must support the JDK it runs on; use `sourceDepsEnv` to
  set `JAVA_HOME` if needed.
- Android and Kotlin Multiplatform projects are not supported.
- Set `version` in the build. Without it, Gradle publishes `unspecified`.

**Example**

```groovy
// greeter/build.gradle
plugins { id 'java-library' }

group = 'example'
version = '1.0.0'
```

## Mill

**Command**

```
./mill --no-server --ticker false __.publishM2Local --m2RepoPath <workdir>/staging
```

Every module extending `PublishModule` is published, with its `publishVersion`.

**Requirements**

- `./mill` in the project (recommended) or `mill` on the `PATH`. Mill 1.x downloads the Mill version
  declared in `build.mill` and, by default, its own JDK on the first run.
- Modules must extend `PublishModule` and define `pomSettings` and `publishVersion`.

**Example**

```scala
// greeter/build.mill
//| mill-version: 1.3.0
package build

import mill.*, scalalib.*, publish.*

object greeter extends ScalaModule with PublishModule {
  def scalaVersion = "3.8.4"
  def publishVersion = "1.0.0"
  def pomSettings = PomSettings(
    description = "greeter",
    organization = "example",
    url = "https://example.com/greeter",
    licenses = Seq(License.MIT),
    versionControl = VersionControl(),
    developers = Nil
  )
}
```

## scala-cli

**Command**

```
scala-cli --power publish . --publish-repository file:///.../staging
```

When `scala-cli` is not on the `PATH`, the plugin falls back to `scala`, because since Scala 3.5 the
`scala` command is scala-cli.

**Requirements.** The project needs a `project.scala` (used for detection) with its publish settings:

```scala
// greeter/project.scala
//> using scala 3.8.4
//> using publish.organization example
//> using publish.name greeter
//> using publish.version 1.0.0
```

## Tested versions

Each tool is covered by a scripted test (`src/sbt-test/source-deps/`):

| Tool | Version | Test |
| --- | --- | --- |
| sbt | 2.0.10 | `local-sbt`, `git-sbt` |
| Maven | 3.9.9 | `local-maven` (reactor with parent) |
| Gradle | 9.8.0 | `local-gradle` |
| Mill | 1.3.0 (via `./mill`) | `local-mill` |
| scala-cli | 1.16.0 (`scala` runner) | `local-scalacli` |

All tests ran on JDK 27 (Zulu), Linux.
