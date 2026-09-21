# Estrutura do Repositório no fecho da Fase 0 (referência histórica)

```text
tuprel/
├── .agents/
├── .claude/
├── .codex/
├── .github/
├── build-logic/
├── docs/
├── plans/
├── project/
├── gradle/
│   ├── wrapper/
│   ├── libs.versions.toml
│   └── verification-metadata.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
└── gradlew.bat
```

A Fase 0 termina com **zero módulos de produto**. `build-logic` é um included
build de infraestrutura, não um product subproject. Os módulos do destino
arquitectural são adicionados apenas na fase que introduz código real, a
começar por `tuprel-schema` na Fase 1; o catálogo completo continua em
`MODULE_CATALOG.md` e os boundaries em
`docs/architecture/MODULE_BOUNDARIES.md`.

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

## Estrutura actual da Fase 1

Além da fundação acima, a build actual contém os primeiros boundaries de
produto:

```text
tuprel/
├── tuprel-schema/
├── tuprel-cli/
├── build-logic/
├── gradle/
├── docs/
├── plans/
└── project/
```

`tuprel-schema` implementa o subconjunto de linguagem aceite pelo RFC-001 e
`tuprel-cli` fornece os comandos iniciais `validate` e `format`. Os restantes
módulos do catálogo continuam fora da build.
