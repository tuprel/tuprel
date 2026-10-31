# Fase 6 - Migration Engine

F6-001 e F6-002A integradas pelos PRs #26/#27. ADRs 0012/0013/0014 aceites,
com executor ainda não autorizado. F6-002B implementa histórico/status read-only
para revisão. Ver [contrato](F6-002B_MIGRATION_STATUS.md).
Desenho pronto para aprovação em [RFC-006](../docs/rfcs/RFC-006-migration-engine-proposal.md),
[contrato CLI](../docs/product/CLI_EXPERIENCE.md). A aprovação arquitectural não
dispensa a revisão de SQL/execução da F6-003.

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
Próxima tarefa: rever F6-002B e contratos específicos F6-003 antes de executor.

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
