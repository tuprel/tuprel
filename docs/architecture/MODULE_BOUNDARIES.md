# Module Boundaries

## Estrutura alvo do monorepo

```text
tuprel/
├── tuprel-schema
├── tuprel-codegen-java
├── tuprel-runtime
├── tuprel-sql
├── tuprel-postgresql
├── tuprel-migrate
├── tuprel-introspection-postgresql
├── tuprel-cli
├── tuprel-spring-boot-starter
├── tuprel-gradle-plugin
├── tuprel-maven-plugin
├── tuprel-testkit
├── tuprel-bom
├── examples/
├── docs/
└── .claude/
```

A Fase 1 declara `tuprel-schema` e o adapter inicial `tuprel-cli`; a Fase 2
acrescenta `tuprel-codegen-java`. A Fase 3 acrescenta `tuprel-sql`,
`tuprel-runtime` e `tuprel-postgresql`, com código e testes reais. Os restantes
módulos continuam planeados. `build-logic` é um included build de
infraestrutura e não faz parte deste grafo de produto.

## Responsabilidades

### `tuprel-schema`

Lexer, parser, AST, source spans, diagnostics, validated schema model.

Não depende de runtime, JDBC, PostgreSQL ou frameworks.

### `tuprel-codegen-java`

Geração determinística de Java a partir de validated schema; rendering em
memória e escrita segura de fontes geradas, sem comportamento de runtime.

Depende de `tuprel-schema`. Não depende de PostgreSQL JDBC.

### `tuprel-sql`

Actualmente: identificadores validados, operações CRUD estruturais, valores
escalares tipados e contratos de renderer. O AST completo de queries fica
para a Fase 4.

Não contém código específico Spring.

### `tuprel-runtime`

Actualmente: DataSource/JDBC lifecycle, invocação da binding do dialecto,
execução CRUD, leitura de
resultados tipada e erros. Transactions, streaming e cliente operacional
gerado continuam planeados.

Pode depender de `tuprel-sql`; não depende de Spring.

### `tuprel-postgresql`

Actualmente: renderer CRUD PostgreSQL, binding JDBC escalar/NULL e entrada
para o runtime com DataSource.
Bindings PostgreSQL avançados e comportamento de dialecto posterior ficam
planeados. Testes de integração usam PostgreSQL real.

Depende dos contratos de `tuprel-sql` e runtime necessários.

### `tuprel-migrate`

Migration model, history, checksums, locking, planner/applier e safety analysis. Integra com dialect/introspection por interfaces explícitas.

### `tuprel-introspection-postgresql`

Leitura de catálogos PostgreSQL e transformação no modelo de schema.

### `tuprel-cli`

Orquestra comandos. Actualmente expõe `validate`, `format` e `generate`,
delegando análise no `tuprel-schema` e geração no `tuprel-codegen-java`; não
deve duplicar lógica do parser, codegen ou migrate.

### `tuprel-spring-boot-starter`

Auto-configuration e integração com `DataSource`, lifecycle e transactions conforme contrato aprovado.

### plugins Gradle/Maven

Integram geração e validação no lifecycle de build. Não implementam ORM.

### `tuprel-testkit`

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
