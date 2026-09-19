# Fase 5 - Relações, Transactions e Concorrência

## Funcionalidades

- 1:1, 1:N, N:N;
- relation writes com comportamento explícito;
- include/select de relações sem lazy loading invisível;
- transactions com commit/rollback claros;
- nested transaction semantics deliberadas;
- batch;
- optimistic locking;
- row locks PostgreSQL;
- query timeout;
- streaming com `AutoCloseable`/lifecycle seguro;
- prevenção/diagnóstico de N+1 onde possível.

## Gate

Testes de concorrência e failure paths são obrigatórios, não apenas happy path.
