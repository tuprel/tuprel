# Fase 6 - Migration Engine

Proposta de desenho incremental em
[`RFC-006`](../docs/rfcs/RFC-006-migration-engine-proposal.md), ainda não aprovada.
Nenhuma implementação desta fase foi iniciada na consolidação da Fase 5.

## Comandos alvo

- `tuprel migrate dev --name <nome>`
- `tuprel migrate deploy`
- `tuprel migrate status`
- `tuprel migrate reset` apenas para ambientes de desenvolvimento e com confirmação forte

## Entregáveis

- schema diff conhecido;
- migration file format;
- history table;
- checksums;
- advisory/concurrency lock;
- drift detection;
- data-loss warnings;
- rename strategy deliberada;
- failure/recovery states;
- preview de SQL.

## Proibição

`migrate deploy` não gera alterações novas a partir do schema actual. Aplica migrations existentes, revistas e versionadas.
