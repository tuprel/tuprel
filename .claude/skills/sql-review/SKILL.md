---
name: sql-review
description: Revê SQL generation e query behavior do Jorvia para correcção, parametrização, PostgreSQL e desempenho.
allowed-tools: Read Grep Glob
---

Revê `$ARGUMENTS`.

Confirma separadamente:

1. estrutura SQL;
2. lista e ordem de bind parameters;
3. quoting de identificadores;
4. null semantics;
5. arrays/JSONB/enums quando aplicável;
6. paginação e ordering determinístico;
7. joins e risco N+1;
8. transacções/locks/timeouts;
9. query plan regressions previsíveis;
10. testes em PostgreSQL real.

Nunca aproves concatenação de input externo em SQL.
