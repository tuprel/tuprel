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
├── tuprel-migrate-postgresql
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
`tuprel-runtime` e `tuprel-postgresql`, com código e testes reais. As Fases 4 e 5 não
criam módulos: estendem estes três, `tuprel-schema` e o código gerado (RFC-003,
RFC-004, RFC-005, ADR-0010, ADR-0011).
A F6-001 acrescenta `tuprel-migrate`, com identificação, ficheiros e checksums
puros (ADR-0012 aceite). F6-002B acrescenta o adapter PostgreSQL read-only
(ADRs 0013/0014 aceites); F6-003B acrescenta o runner no mesmo adapter,
conforme ADRs 0015–0017. Os restantes módulos
continuam planeados. `build-logic` é um included build de
infraestrutura e não faz parte deste grafo de produto.

`test-support/src/main/java` contém apenas suporte local partilhado pelas
fixtures de integração e pelo exemplo executável. Não é um subprojecto nem
uma dependência dos artefactos de produto; os dois consumidores compilam esses
fontes explicitamente. O carregamento opcional do `.env` fica aqui, fora do core,
do driver de produção e da configuração Gradle.

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
estruturais com e sem `RETURNING`, inserção multi-linha, escrita condicional,
verificação de versão, o modelo estrutural de queries (`SqlQuery` com row locks,
`SqlCondition`, `SqlOrder`) e contratos de renderer. Não contém
texto SQL de dialecto.

Não contém código específico Spring.

### `tuprel-runtime`

DataSource/JDBC lifecycle, invocação da binding do dialecto, execução de CRUD
e de queries estruturais, leitura de resultados tipada e erros. O package
`dev.tuprel.runtime.query` contém a API tipada usada pelo cliente gerado:
colunas por model, condições, query imutável, projecções, cursor, relações
incluídas, row locks e `ModelOperations`. Transacções explícitas com
savepoints, timeouts e streaming vivem em `TuprelDatabase`.

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

Implementado na F6-001: IDs, snapshots imutáveis, SHA-256, formato versionado,
leitura limitada e validação de ficheiros/paths. Depende exclusivamente de
`java.base`; não tem JDBC, schema parser, runtime ou dialecto. A CLI consome
o contrato de formato no init. A F6-002A acrescenta criação offline de drafts,
com exclusão entre criadores via lock de filesystem, sem dependências novas.

F6-002B acrescenta snapshots tipados de histórico, comparação pura de integridade
e o port MigrationHistoryReader, consumido pela CLI e implementado pelo adapter.
Locking de DB, planner/applier e safety analysis PostgreSQL ficam no adapter.
O domínio mantém apenas java.base; JDBC nunca entra neste módulo.

### `tuprel-migrate-postgresql`

ADR-0013 aceite: CLI → migrate-postgresql → migrate/sql, sem aresta do driver
CRUD para migrate. Implementa leitura de histórico via DataSource/JDBC numa
transacção read-only repeatable-read. Valida metadados, constraints, permissões
e linhas; o reader nunca cria tabelas ou executa migrations. Recebe driver do consumidor, sem
dependência de PG obrigatória no adapter; a CLI compõe runtime PG 42.7.14.
Não depende de runtime, postgresql CRUD, schema ou codegen. Migrate não depende dele.

F6-003A acrescenta gramática DDL/AST/renderer e compilador de planos/riscos/digest
offline no mesmo adapter (ADRs 0015/0016 aceites). Reutilizam MigrationFile,
MigrationStatus e SqlIdentifier; não abrem ficheiros/connections nem executam
SQL. A dependência existente de sql é exportada como api porque o AST público
usa SqlIdentifier. Sem novo módulo, ciclo ou dependência externa.
F6-003B acrescenta PostgresqlMigrationRunner, com connection dedicada,
aprovação revalidada sob lock, gates de catálogo, bootstrap e transacção por
migration. A inspecção é read-only e separada da execução explícita; o reader
de status permanece inalterado. Resultados preservam prefixos confirmados e
OUTCOME_UNKNOWN, sem retries. O JDBC e estes gates continuam no adapter,
sem arestas novas nem reutilização do lifecycle CRUD.

### `tuprel-introspection-postgresql`

Leitura de catálogos PostgreSQL e transformação no modelo de schema.

### `tuprel-cli`

Orquestra comandos. Na F6-001 expõe help/version, init, `validate`, `format` e `generate`,
delegando análise no `tuprel-schema`, geração no `tuprel-codegen-java` e formato
de migrations no `tuprel-migrate`. A F6-002A orquestra `migrate create`, mantendo
as regras de criação no módulo migrate; não
deve duplicar lógica do parser, codegen ou migrate.
F6-002B apresenta status e compõe a configuração explícita de ambiente e adapter;
SQL e comparações permanecem fora da CLI. Testes e dotenv não são configuração
de produção nem dependências dos artefactos publicados.

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
