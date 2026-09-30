# Fase 5 - Relações, Transactions e Concorrência

Estado: concluída para o subconjunto definido abaixo. Contratos em
`docs/rfcs/RFC-003-relation-loading.md` e
`docs/rfcs/RFC-005-transactions-concurrency.md`; decisões estruturais em
`docs/adr/ADR-0011-explicit-relations-and-transactions.md`.

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

## Entregue

- **Schema:** emparelhamento de relações (`RelationGraph`, `SEM-021` a
  `SEM-024`) e `@version` (`SEM-025`, `SEM-026`).
- **Relações:**
  - 1:1, 1:N e auto-relações;
  - N:N através de um model de junção explícito;
  - componentes `TuprelRelation` nos records;
  - `P.include` com includes aninhados, filtrados e ordenados;
  - carregamento em lote com uma query por relação e nível, que impede o N+1
    por construção;
  - escrita de relações pelas colunas de chave estrangeira, sem persistência
    de grafos.
- **Transacções:**
  - `TuprelDatabase.transaction` e `TuprelClient.transaction`, com commit,
    rollback e restauro da connection;
  - isolamento, read-only e prazo;
  - blocos aninhados com savepoints;
  - handles confinados ao bloco e à thread.
- **Concorrência:**
  - `Query.lock(RowLock)` com `FOR UPDATE`/`FOR SHARE` e
    `NOWAIT`/`SKIP LOCKED`, apenas dentro de transacções;
  - `Query.timeout`;
  - optimistic locking com `updateById(id, expectedVersion, input)` e
    `OptimisticLockException`.
- **Volume:**
  - `stream` com `TuprelStream` (`AutoCloseable`, fetch size, fechado pela
    transacção);
  - `createMany` com `INSERT` multi-linha e `DEFAULT`, atómico num statement
    e exigindo transacção quando precisa de vários;
  - `updateMany` e `deleteMany` com condição obrigatória.

## Evidência do gate

- Unit tests do lifecycle transaccional com eventos JDBC em ordem: commit,
  rollback, falha de commit, savepoints, restauro, handles escapados, outra
  thread, prazo e streams abertos.
- Integração em PostgreSQL 17.11 real:
  - rollback por excepção e por violação de foreign key;
  - savepoint parcial;
  - `REPEATABLE_READ` contra `READ_COMMITTED` com commit concorrente;
  - read-only (`25006`);
  - duas transacções concorrentes com espera, `NOWAIT` (`55P03`),
    `SKIP LOCKED`, timeout de query e prazo de transacção (`57014`);
  - oito escritores optimistas concorrentes, dos quais exactamente um vence;
  - batch atómico perante violação de unicidade (`23505`);
  - contagem exacta de statements de includes com 3 e com 1 200 linhas.
- Golden files e contratos de compilação: um include de outro model não
  compila, e um model versionado não oferece update sem versão.

## Fora desta fase

- relação N:N implícita, que precisa das migrations;
- filtros sobre relações (`some`/`every`) e escrita aninhada de relações;
- upsert e `deleteById` com versão;
- retry automático de serialização e deadlocks;
- `@map`/`@@map`, acções referenciais e nomes de relação;
- integração com gestores de transacções de frameworks (Fase 7).
