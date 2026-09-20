# Agent Instructions

Este projecto é o Tuprel ORM, uma infraestrutura de persistência Java.

As instruções canónicas para agentes estão em `CLAUDE.md`. Antes de implementar código, lê também `START_HERE.md`, `plans/MASTER_PLAN.md`, o plano da fase actual, os ADRs relevantes e as regras em `.claude/rules/`.

Regras essenciais:

- Java 21 baseline.
- PostgreSQL primeiro.
- Sem dependência de JPA/Hibernate no core.
- Prepared statements e binding para valores SQL.
- Sem lazy loading ou dirty checking implícitos por omissão.
- Testes de integração PostgreSQL usam PostgreSQL real/Testcontainers.
- Não publicar, fazer push ou executar acções destrutivas sem autorização explícita.
- Exemplos públicos Java usam tipos explícitos em vez de `var`.
