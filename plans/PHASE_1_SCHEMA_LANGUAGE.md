# Fase 1 - Schema Language

## Scope

Definir formalmente `schema.tuprel`: lexer, parser, AST, diagnostics, validator e formatter.

## Mínimo funcional

- datasource/generator config;
- model;
- scalar types;
- nullable/list cardinality;
- enum;
- `@id`, `@unique`, defaults básicos;
- `@@index`, `@@unique`;
- relações apenas no nível sintáctico/semântico necessário para preparar a fase seguinte;
- source spans e mensagens de erro úteis;
- `tuprel validate` e `tuprel format` numa CLI inicial.

## Testes obrigatórios

Positive/negative fixtures, unicode, duplicate symbols, invalid types, cycles/relation diagnostics quando definidos, formatter idempotence e fuzz/property testing se viável.

## Gate

Nenhum codegen começa enquanto o validated schema model não estiver estável o suficiente e documentado.
