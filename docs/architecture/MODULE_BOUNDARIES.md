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
`tuprel-runtime` e `tuprel-postgresql`, com código e testes reais. A Fase 4 não
cria módulos: estende estes três e o código gerado (RFC-004, ADR-0010). Os
restantes módulos continuam planeados. `build-logic` é um included build de
infraestrutura e não faz parte deste grafo de produto.

## Responsabilidades

### `tuprel-schema`

Lexer, parser, AST, source spans, diagnostics, validated schema model.

Não depende de runtime, JDBC, PostgreSQL ou frameworks.

### `tuprel-codegen-java`

Geração determinística de Java a partir de validated schema; rendering em
memória e escrita segura de fontes geradas, sem comportamento de runtime.

Depende de `tuprel-schema`. Não depende de `tuprel-runtime` nem de PostgreSQL
JDBC. O código que produz em `where`, `order`, `client` e `TuprelClient`
compila contra `tuprel-runtime` no projecto consumidor (ADR-0010). Os testes
do módulo compilam esse código com o runtime no classpath de teste.

### `tuprel-sql`

Identificadores validados, valores escalares tipados, operações CRUD
estruturais com e sem `RETURNING`, o modelo estrutural de queries
(`SqlQuery`, `SqlCondition`, `SqlOrder`) e contratos de renderer. Não contém
texto SQL de dialecto.

Não contém código específico Spring.

### `tuprel-runtime`

DataSource/JDBC lifecycle, invocação da binding do dialecto, execução de CRUD
e de queries estruturais, leitura de resultados tipada e erros. O package
`dev.tuprel.runtime.query` contém a API tipada usada pelo cliente gerado:
colunas por model, condições, query imutável, projecções, cursor e
`ModelOperations`. Transactions e streaming continuam planeados.

Pode depender de `tuprel-sql`; não depende de Spring.

### `tuprel-postgresql`

Renderer PostgreSQL de CRUD e queries, binding JDBC escalar/NULL e entrada
para o runtime com DataSource. Bindings PostgreSQL avançados e comportamento
de dialecto posterior ficam planeados. Testes de integração usam PostgreSQL
real e um cliente gerado pela CLI a partir de um schema de teste. Por isso, o
source set `integrationTest` resolve `tuprel-cli` num classpath próprio, só de
build.

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
