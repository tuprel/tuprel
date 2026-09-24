# Fase 3 - Runtime PostgreSQL Mínimo

Estado: concluída para o subconjunto definido abaixo. Contratos e limites em
`docs/adr/ADR-0009-runtime-postgresql-foundation.md`.

## Scope

Executar operações básicas reais contra PostgreSQL.

## Entregáveis

- DataSource integration;
- connection/statement/result lifecycle;
- SQL renderer PostgreSQL inicial;
- bind model;
- type mapping básico;
- row mapper gerado ou type-safe;
- create/find/update/delete mínimos;
- error mapping base;
- integration tests Testcontainers.

## Segurança

Nenhum valor externo entra na string SQL. Testes de injection são obrigatórios antes do gate.

## Gate de saída

O renderer produz SQL e binds separados; `tuprel-runtime` executa operações
explícitas com recursos JDBC fechados deterministicamente. Unit tests cobrem
estrutura, renderer e falhas de lifecycle. A suite `integrationTest` de
`tuprel-postgresql` usa PostgreSQL 17.11 real via Testcontainers para CRUD,
tipos escalares, nulabilidade, erros e regressão de SQL injection. `check` da
raiz agrega esta suite e deve passar na CI Linux com Docker. Não inclui query
DSL, relação loading, transacções, JSONB nem cliente gerado operacional.
