# Fase 6 - Migration Engine

## Comandos alvo

- `jorvia migrate dev --name <nome>`
- `jorvia migrate deploy`
- `jorvia migrate status`
- `jorvia migrate reset` apenas para ambientes de desenvolvimento e com confirmação forte

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
