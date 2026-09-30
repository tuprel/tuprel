# Fase 4 - Query API Type-safe

Estado: concluída para o subconjunto definido abaixo. Contrato em
`docs/rfcs/RFC-004-type-safe-query-api.md`; dependência do código gerado e
mapeamento físico em `docs/adr/ADR-0010-generated-client-runtime-dependency.md`.

## Scope

Ergonomia de queries sem strings mágicas para o caso comum.

## Funcionalidades

- eq/ne/lt/lte/gt/gte;
- contains/startsWith/endsWith com semântica definida;
- in/notIn;
- AND/OR/NOT;
- null predicates;
- ordering;
- select;
- count/exists;
- offset/take;
- cursor pagination com ordering determinístico;
- SQL preview.

## Gate

API review pelo `java-api-reviewer`, SQL review e contract tests antes de ampliar relações.

## Entregue

- Modelo estrutural em `tuprel-sql`:
  - `SqlQuery`, com selecções de colunas, `count` e `exists`;
  - `SqlCondition` e `SqlOrder`;
  - `INSERT`/`UPDATE ... RETURNING`.
- Renderer PostgreSQL:
  - todos os valores, incluindo `LIMIT`/`OFFSET`, são binds;
  - identificadores são citados;
  - `LIKE ... ESCAPE '!'` é literal e sensível a maiúsculas;
  - statements com mais de 65 535 binds são rejeitados.
- API tipada em `dev.tuprel.runtime.query`:
  - colunas por model e condições do mesmo model;
  - query imutável, ordenação e projecções;
  - cursor opaco e operações por model sobre `TuprelDatabase`.
- Geração do cliente:
  - `P.where` com colunas tipadas;
  - `P.order`;
  - `P.client` com um cliente por model operacional e `TuprelClient`.
- Operações:
  - `create`, `findById`, `findMany`, `findFirst`, `findManyCursor`,
    `select`, `count`, `exists`, `updateById`, `deleteById` e `preview`;
  - cada uma executa um único statement.

## Evidência do gate

- Contract tests de compilação provam que uma condição de outro model e
  `contains` numa coluna UUID não compilam.
- Golden files e compilação real com `javac --release 21 -Xlint:all -Werror`
  cobrem o código gerado, incluindo models com nomes iguais a tipos do
  runtime.
- Unit tests do modelo estrutural, do renderer (SQL e binds separados), da API
  tipada, do lifecycle JDBC das queries e do descodificador de cursor, com
  tokens hostis.
- Integration tests em PostgreSQL 17.11 real via Testcontainers, sobre código
  gerado pela CLI `tuprel generate`. Cobrem:
  - CRUD e cada operador;
  - semântica de `NULL` e padrões literais;
  - paginação e projecções;
  - cursor com empates e inserções entre páginas;
  - restrições e injecção através da API gerada.
- Revisão de API e SQL registada no pull request da fase.

## Fora desta fase

Relações e `include` (RFC-003), transacções, locks, batch,
`updateMany`/`deleteMany`/upsert, agregações além de `count`, página
estruturada com totais, cursor para trás, streaming, `@map`/`@@map`, enums
nativos PostgreSQL, `Json` e arrays. Models com campos lista ou `Json` recebem
colunas tipadas, mas ainda não têm cliente gerado.
