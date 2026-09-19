# Estrutura Esperada do Repositório depois da Fase 0

```text
jorvia/
├── .claude/
├── .github/
├── docs/
├── plans/
├── gradle/
│   ├── wrapper/
│   └── libs.versions.toml
├── build-logic/
│   └── ... convention plugins, se justificados
├── jorvia-schema/
│   └── src/{main,test}/java/...
├── jorvia-codegen-java/
├── jorvia-sql/
├── jorvia-runtime/
├── jorvia-postgresql/
├── jorvia-migrate/
├── jorvia-introspection-postgresql/
├── jorvia-cli/
├── jorvia-spring-boot-starter/
├── jorvia-gradle-plugin/
├── jorvia-maven-plugin/
├── jorvia-testkit/
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
build/generated/sources/jorvia/main/
```

Maven:

```text
target/generated-sources/jorvia/
```

Nunca gerar por omissão em `src/main/java`.
