# Fase 4 - Query API Type-safe

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
