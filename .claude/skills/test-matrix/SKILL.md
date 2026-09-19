---
name: test-matrix
description: Desenha ou revê a matriz de testes para uma funcionalidade ou release do Jorvia.
allowed-tools: Read Grep Glob
---

Para `$ARGUMENTS`, produz casos de teste organizados por:

- unit;
- parser/validator golden;
- codegen compilation;
- PostgreSQL integration;
- concurrency;
- migration;
- negative/security;
- compatibility;
- performance/benchmark quando relevante.

Inclui casos limite, unicode, nulls, erros e regressões. Não uses H2 como prova de compatibilidade PostgreSQL.
