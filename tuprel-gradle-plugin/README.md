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
CLI classpath, Java 21 launcher and generated outputs. The CLI-owned source
directory is `build/generated/sources/tuprel/main` relative to the project.
Declare Tuprel runtime dependencies in the consumer as needed.

Generation retains the CLI ownership checks. Remote build caching is off
because restoring files would bypass those checks. The plugin runs neither
migrations nor database operations. Plugin Portal publication and a wider
Gradle compatibility matrix remain pending.
