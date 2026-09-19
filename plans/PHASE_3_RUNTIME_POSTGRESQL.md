# Fase 3 - Runtime PostgreSQL Mínimo

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
