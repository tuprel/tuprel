# Master Plan

O desenvolvimento avança por gates. Não saltar fases porque uma funcionalidade futura parece interessante.

## Fase 0 - Fundação de engenharia

**Concluída.** Build preparado para futuros módulos, quality gates,
CI configurada, security baseline, test infrastructure, package naming
provisório e documentação operacional. Nenhum módulo de produto é criado sem
código real.

## Fase 1 - Schema language

**Concluída.** RFC-001 foi aceite para o subconjunto inicial. `tuprel-schema`
fornece lexer/parser, AST, diagnostics, model/enums/scalars, atributos
fundamentais, formatter e validator; `tuprel-cli` expõe `validate` e `format`.

## Fase 2 - Java code generation

**Concluída.** RFC-002 aceite para o subconjunto implementado.
`tuprel-codegen-java` gera model values, enums, metadata e inputs a partir do
schema validado, com output determinístico, escrita segura e compilation tests.
`tuprel generate` expõe a geração.

## Fase 3 - Runtime PostgreSQL mínimo

**Concluída para o subconjunto do plano.** `tuprel-sql`, `tuprel-runtime` e
`tuprel-postgresql` fornecem CRUD estrutural mínimo, DataSource/JDBC lifecycle,
SQL + binds, mapping tipado, erros e integração PostgreSQL real. O cliente
operacional gerado e a query API permanecem nas fases seguintes.

## Fase 4 - Query API type-safe

**Concluída para o subconjunto do plano.** O RFC-004 e o ADR-0010 definem o
cliente operacional gerado sobre o runtime da Fase 3. Inclui filtros tipados
por model, composição lógica, predicados de `NULL`, ordering, projecções,
`count`/`exists`, offset e cursor pagination determinística, e SQL preview.
Relações, `include` e transacções ficam para a Fase 5.

## Fase 5 - Relações e transacções

1:1, 1:N, N:N, include explícito, transactions, batch, optimistic locking, locks/timeouts/streaming essenciais.

## Fase 6 - Migration engine

Diff, migration files, history, checksum, locking, drift, safety, dev/deploy/status/reset-dev.

## Fase 7 - Developer integrations

CLI madura, Spring Boot starter, Gradle plugin, seed e integração de generated sources.

## Fase 8 - Introspection e db pull

Catálogos PostgreSQL -> internal schema model -> `schema.tuprel`, com naming e round-trip tests.

## Fase 9 - PostgreSQL avançado e diagnostics

JSONB, arrays, tipos prioritários, raw SQL seguro, SQL preview, EXPLAIN, observability e performance.

## Fase 10 - Maven, compatibilidade e hardening 1.0

Maven plugin, API compatibility, documentação completa, benchmarks, security hardening, supply-chain/release pipeline.

## Fase 11 - Release candidate

Freeze de API, migration format, schema language version, RC testing e critérios em `RELEASE_1_0.md`.

## Pós 1.0

Studio, reactive/R2DBC, novos dialectos e outras features só entram depois de dados reais de utilização e ADRs próprios.
