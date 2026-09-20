# Fase 8 - PostgreSQL Introspection

## Comando alvo

`tuprel db pull`

## Entregáveis

- leitura segura de schemas/tables/columns/types;
- PK/FK/unique/indexes;
- enums;
- defaults relevantes;
- mapping de nomes físicos para nomes Java/schema;
- diagnostics para features PostgreSQL não representáveis;
- round-trip tests.

Metadata da base é input não confiável para codegen e filesystem.
