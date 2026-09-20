# Master Plan

O desenvolvimento avança por gates. Não saltar fases porque uma funcionalidade futura parece interessante.

## Fase 0 - Fundação de engenharia

Build, módulos iniciais, quality gates, CI, security baseline, test infrastructure, package naming provisório e documentação operacional.

## Fase 1 - Schema language

Lexer/parser, AST, diagnostics, model/enums/scalars, atributos fundamentais, formatter e validator.

## Fase 2 - Java code generation

Model types, metadata, inputs e base do generated client com output determinístico e compilation tests.

## Fase 3 - Runtime PostgreSQL mínimo

DataSource/JDBC lifecycle, SQL + binds, row mapping, errors e CRUD mínimo.

## Fase 4 - Query API type-safe

Filtros, composição lógica, ordering, select, count, pagination e API ergonomics.

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
