# Estrutura Esperada do Repositório depois da Fase 0

```text
tuprel/
├── .claude/
├── .github/
├── docs/
├── plans/
├── gradle/
│   ├── wrapper/
│   └── libs.versions.toml
├── build-logic/
│   └── ... convention plugins, se justificados
├── tuprel-schema/
│   └── src/{main,test}/java/...
├── tuprel-codegen-java/
├── tuprel-sql/
├── tuprel-runtime/
├── tuprel-postgresql/
├── tuprel-migrate/
├── tuprel-introspection-postgresql/
├── tuprel-cli/
├── tuprel-spring-boot-starter/
├── tuprel-gradle-plugin/
├── tuprel-maven-plugin/
├── tuprel-testkit/
├── examples/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
└── gradlew.bat
```

A Fase 0 deve evitar módulos vazios sem valor. Pode começar com subconjunto mínimo e adicionar os restantes quando a fase correspondente iniciar, desde que `MODULE_BOUNDARIES.md` continue a descrever o destino.

## Generated sources numa aplicação consumidora

Gradle:

```text
build/generated/sources/tuprel/main/
```

Maven:

```text
target/generated-sources/tuprel/
```

Nunca gerar por omissão em `src/main/java`.
