[Documentation](../README.md) / Getting started

# Installation and build from source

Use a source checkout while Tuprel is in development preview. There is no Maven
Central dependency, binary release, installer or supported `curl | sh` installation.

## Prerequisites

- JDK 21, available to the Wrapper through JAVA_HOME or the local Java installation.
- Git and network access for the Wrapper and verified build dependencies.
- Bash on Linux or PowerShell on Windows.

You do not need global Gradle, Docker or PostgreSQL to build and try the CLI.
PostgreSQL is needed to execute database queries; complete repository verification
also includes real PostgreSQL tests.

## Build the launcher

```bash
git clone https://github.com/tuprel/tuprel.git
cd tuprel
./gradlew --version
./gradlew :tuprel-cli:installDist --dependency-verification strict
```

On PowerShell, use `.\gradlew.bat` in place of `./gradlew`.
Check that the reported JVM uses JDK 21. The build does not provision a missing JDK.

The local distribution contains launchers and their supporting JARs:

```text
tuprel-cli/build/install/tuprel/
  bin/tuprel
  bin/tuprel.bat
  lib/
```

Keep the distribution together. Invoke the launcher by its absolute path, or
add its bin directory to your own PATH. The build does neither. Java 21 is still required.

## Use an existing Java project

Run the launcher from that project's directory. `tuprel init` creates Tuprel
files only; it does not edit builds, install dependencies, create a database
or read a production connection configuration.

Inspect the working [playground build](../../examples/tuprel-playground/build.gradle.kts).
It uses a Gradle composite build to consume this checkout without publishing.
Its provisional dev.tuprel coordinates are local substitution identifiers,
not dependencies available from a public repository.

Continue with [Quickstart](quickstart.md) and the
[Java client guide](../guides/java-client.md). Contributors running a full build
should follow [Build and Test](../development/BUILD_AND_TEST.md).
A CLI build alone does not validate database integration.
