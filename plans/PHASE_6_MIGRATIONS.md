# Fase 6 - Migration Engine

F6-001/F6-002A/F6-002B integradas pelos PRs #26/#27/#28; histórico/status é
read-only. F6-003A1 integrada no #30, com gramática/AST/preview offline.
F6-003A2 (plano/digest) integrada no #31. ADRs 0012–0017 aceites.
A entrega F6-003B disponibiliza o runner Java transaccional, com gates,
prefixos confirmados e inspecção para reconciliação explícita. A F6-003C expõe
deploy CLI com dry-run e digest revisto; drift completo permanece na F6-005.
Ver [contrato do status](F6-002B_MIGRATION_STATUS.md),
[RFC-006](../docs/rfcs/RFC-006-migration-engine-proposal.md) e
[contrato CLI](../docs/product/CLI_EXPERIENCE.md).

## Entregas pequenas

| Entrega | Âmbito e gate |
|---|---|
| F6-001 | CLI help/version/init/dry-run e modelo puro de ficheiros; sem JDBC |
| F6-002A | Create offline após aprovação do formato; draft identificado, sem aplicar SQL |
| F6-002B | Adapter/histórico aprovados; status read-only com testes PostgreSQL real |
| F6-003 | Gramática SQL, aprovação, executor transaccional, locks e falhas; deploy só então |
| F6-004 | Diff/preview conservador e classificação de riscos; sem auto-execução destrutiva |
| F6-005 | Catálogo parcial/drift e experiência dev; desconhecidos exigem revisão |
| F6-006 | E2E, documentação, plataformas e tarefa separada de adopção do playground |

Create e status são subtarefas separadas: escrita offline não deve depender de
aprovar/implementar uma connection de DB. As entregas mantêm o âmbito do pedido.
Integração #28 → #30 → #31 concluída com testes locais e CI/CodeQL main
aprovados. Evidência e SHAs em [PHASE_6_EXECUTION](PHASE_6_EXECUTION.md).
Runner F6-003B dividido em definição canónica, sessão/locks, uma migration
atómica e sequências/falhas/reconciliação, com testes do contrato aceite.

## Comandos alvo

- `tuprel migrate create --name <nome>`
- `tuprel migrate dev --name <nome>`
- `tuprel migrate deploy`
- `tuprel migrate status`
- `tuprel migrate diff`

`migrate create` e `migrate status` são os comandos implementados. Reset/resolve e migrations
não transaccionais ficam fora do MVP. O help actual só anuncia código funcional.

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

## Playground e validação

Bootstrap SQL continua temporário. Substituí-lo exige tarefa própria, baseline
contra o catálogo real, preservação de dados e regressões; nunca apagar dados
existentes para demonstrar migrations. Testes locais usam PostgreSQL 17 em
tuprel_devtest com schemas isolados, sem Docker; CI mantém containers explícitos.
Executar builds da raiz e do playground sequencialmente. Java 21 inalterado.
