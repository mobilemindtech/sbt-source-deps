# Getting started

This guide adds a source dependency to an sbt 2 project and shows what happens when its sources
change.

## 1. Install the plugin

The plugin is not published yet. Publish it locally from this repository:

```
cd sbt-source-deps
sbt publishLocal
```

Then add it to your project:

```scala
// project/plugins.sbt
addSbtPlugin("br.com.mobilemind" % "sbt-source-deps" % "0.1.0-SNAPSHOT")
```

The plugin is enabled automatically on every JVM project. Nothing changes until you declare a
source dependency.

## 2. Declare a source dependency

Suppose you have a library next to your project, in `../greeter`. It can be an sbt, Mill, Gradle,
Maven or scala-cli project:

```
workspace/
├── greeter/          ← the library (any supported build tool)
│   ├── build.sbt
│   └── src/main/scala/greeter/Greeter.scala
└── app/              ← your project
    ├── build.sbt
    └── project/plugins.sbt
```

```scala
// app/build.sbt
scalaVersion := "3.8.4"

sourceDependencies += SourceDep.local(file("../greeter"))
```

Ignore the plugin's working directory:

```
# app/.gitignore
.source-deps/
```

## 3. Compile

```
app> sbt compile
[info] [source-deps] building ../greeter with sbt (sources changed)
[info] [source-deps] greeter-1a2b3c4d: sbt --server --batch ... publish
[info] compiling 1 Scala source ...
[success]
```

The plugin built `greeter` with its own build, published it as
`example:greeter_3:1.0.0+src.6bd572a241a1`, and added that module to your dependencies. You can now
use `greeter.Greeter` in your code.

Run `compile` again and nothing is rebuilt:

```
app> sbt sourceDepsStatus
[info] greeter-1a2b3c4d: ../greeter -> up to date
```

## 4. Change the dependency

Edit `../greeter/src/main/scala/greeter/Greeter.scala`, then compile `app` again:

```
app> sbt compile
[info] [source-deps] building ../greeter with sbt (sources changed)
...
```

The library was rebuilt under a new version (`1.0.0+src.<new hash>`), and `app` was recompiled
against it. The artifacts of the previous version are deleted.

## 5. Use a git repository instead

```scala
sourceDependencies += SourceDep.git("https://github.com/example/greeter.git", ref = "main")
```

The repository is cloned into `.source-deps/greeter-<hash>/src` on the first build. **New commits on
`main` are not picked up automatically.** That keeps builds reproducible. To move to the latest
commit, run:

```
app> sbt sourceDepsUpdate
[info] [source-deps] fetching https://github.com/example/greeter.git
[info] [source-deps] checking out main (9f8e7d6c5b) in .source-deps/greeter-.../src
[info] [source-deps] building https://github.com/example/greeter.git@main with sbt (sources changed)
```

Next: [Usage and examples](usage.md).
