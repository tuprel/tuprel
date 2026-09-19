# Module Boundaries

## Estrutura alvo do monorepo

```text
jorvia/
├── jorvia-schema
├── jorvia-codegen-java
├── jorvia-runtime
├── jorvia-sql
├── jorvia-postgresql
├── jorvia-migrate
├── jorvia-introspection-postgresql
├── jorvia-cli
├── jorvia-spring-boot-starter
├── jorvia-gradle-plugin
├── jorvia-maven-plugin
├── jorvia-testkit
├── jorvia-bom
├── examples/
├── docs/
└── .claude/
```

A Fase 0 pode criar apenas os módulos necessários para estabelecer o grafo e deixar módulos posteriores como projectos declarados/documentados quando isso reduzir complexidade inicial.

## Responsabilidades

### `jorvia-schema`

Lexer, parser, AST, source spans, diagnostics, validated schema model.

Não depende de runtime, JDBC, PostgreSQL ou frameworks.

### `jorvia-codegen-java`

Geração determinística de Java a partir de validated schema.

Depende de `jorvia-schema`. Não depende de PostgreSQL JDBC.

### `jorvia-sql`

Representação de query/SQL AST, bind model e contratos de dialect.

Não contém código específico Spring.

### `jorvia-runtime`

Lifecycle, query execution abstractions, mapping, transactions, streaming, errors e public client contracts.

Pode depender de `jorvia-sql`; não depende de Spring.

### `jorvia-postgresql`

Renderer/dialect PostgreSQL, bindings de tipos e comportamento específico.

Depende dos contratos de `jorvia-sql` e runtime necessários.

### `jorvia-migrate`

Migration model, history, checksums, locking, planner/applier e safety analysis. Integra com dialect/introspection por interfaces explícitas.

### `jorvia-introspection-postgresql`

Leitura de catálogos PostgreSQL e transformação no modelo de schema.

### `jorvia-cli`

Orquestra comandos. Não deve duplicar lógica do parser, codegen ou migrate.

### `jorvia-spring-boot-starter`

Auto-configuration e integração com `DataSource`, lifecycle e transactions conforme contrato aprovado.

### plugins Gradle/Maven

Integram geração e validação no lifecycle de build. Não implementam ORM.

### `jorvia-testkit`

Fixtures, helpers e contract suites reutilizáveis. Nunca vira uma dependência runtime de produção.

## Dependências proibidas

- schema -> runtime
- schema -> PostgreSQL JDBC
- runtime -> Spring
- runtime -> Gradle/Maven APIs
- PostgreSQL -> Spring
- codegen -> Spring
- qualquer ciclo entre módulos

## Regra para novos módulos

Não criar um módulo só para "organizar" poucas classes. Um novo módulo precisa de boundary real, consumidor diferente, ciclo evitado ou política de dependência distinta.
