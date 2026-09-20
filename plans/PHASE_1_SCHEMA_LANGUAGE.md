# Fase 1 - Schema Language

## Gate de design

O primeiro trabalho da Fase 1 é criar e rever
`docs/rfcs/RFC-001-schema-language.md`. O RFC define pelo menos grammar,
cardinalidade/nulabilidade, source spans, diagnostics e regras do formatter
necessárias ao mínimo funcional abaixo.

RFC-002 Java Client API e RFC-003 Relation Loading continuam por criar, mas
bloqueiam as fases que implementam esses contratos, não o arranque do lexer e
parser depois de RFC-001 estar suficientemente definido.

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
