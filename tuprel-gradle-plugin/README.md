# Gradle code generation

The development plugin uses a trusted installed Tuprel CLI. Include this
build from source for local evaluation or use the external TestKit fixture.

```groovy
plugins {
    id 'java'
    id 'dev.tuprel.codegen'
}
tuprelCodegen {
    cliClasspath.from(fileTree('/path/to/tuprel/lib') { include '*.jar' })
}
```

Run `tuprelValidate` to validate the default `tuprel/schema.tuprel`.
Java compilation runs `tuprelGenerate` first, and Gradle tracks the schema,
CLI classpath, selected Java launcher and generated outputs. The CLI-owned source
directory is `build/generated/sources/tuprel/main` relative to the project.
Declare Tuprel runtime dependencies in the consumer as needed.

Generation retains the CLI ownership checks. Build caching of generation is
off because restoring files would bypass those checks. The plugin runs neither
migrations nor database operations. Configuration cache and up-to-date checks
remain available. A clean build runs generation again, including when the
consumer enables its build cache.

## Java selection

The CLI launcher follows the Java toolchain configured by the consumer.
With no explicit toolchain, it uses the JVM running Gradle. The provider is
resolved only when a Tuprel task needs it. A Java 17 build can generate code
using its own JDK without discovering or downloading Java 21.

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
```

Java 21 projects retain Java 21 when their daemon or configured toolchain
uses it. A missing toolchain fails through Gradle's toolchain diagnostics.
A selected CLI launcher below Java 17 is rejected before execution. Advanced
consumers can override the existing task `launcher` property with a compatible
`JavaLauncher` provider.

Use `--info` to see the CLI Java version and executable path. Changing the
launcher, schema or CLI classpath invalidates generation inputs. Handwritten
files and edited generated sources retain the CLI's fail-closed protection.

The distribution fixture stages the plugin marker and implementation in a
temporary Maven repository. It runs the same consumer offline, resolving
the explicit plugin ID and fixture version without TestKit classpath
injection.

```powershell
pwsh -File test-support/distribution/Verify-StagedGradlePlugin.ps1
```

## LTS certification fixture

`Verify-GradlePluginLts.ps1` builds the plugin once with JDK 21 and release 17,
checks its five production classes, marker, POM and JVM variants, then resolves
the same staging bytes by plugin ID from independent projects outside the
checkout. It runs Gradle 9.7.1, 9.8.2 and 9.9.1 on Java 17, 21 and 25.
Each consumer compiles generated sources with a real Java 17 compiler.

```powershell
pwsh -File test-support/distribution/Verify-GradlePluginLts.ps1 `
  -Jdk17 $jdk17 -Jdk21 $jdk21 -Jdk25 $jdk25
```

The proof uses SHA-256-verified official Gradle ZIPs, an isolated Gradle home
and a frozen artifact verification manifest. It disables automatic toolchain
discovery and downloads. It compares generated files across cells and checks
incremental execution, configuration cache, schema/classpath changes, clean,
ownership and tampered artifact rejection.

Linux CI also uses `-Java17Container` to run the consumers in a pinned,
unprivileged Java 17-only container without network access or mounted host
JDKs. Local Windows execution uses no Docker.

This is development staging. Java 21 remains the effective public product
minimum pending the complete ADR-0024 certification and activation decision.
Plugin Portal publication remains pending.
