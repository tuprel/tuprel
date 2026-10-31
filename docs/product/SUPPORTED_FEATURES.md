# Matriz de Funcionalidades Planeadas

Este documento separa o que deve existir no produto estável do que não deve bloquear a primeira release.

O subconjunto inicial de schema definido no RFC-001 já está implementado em
`tuprel-schema`, com `validate` e `format` disponíveis em `tuprel-cli`. Os
tipos Java gerados do subconjunto RFC-002 e `generate` também estão disponíveis
na Fase 2. A Fase 3 acrescentou CRUD estrutural mínimo com acesso real a
PostgreSQL. A Fase 4 acrescentou o cliente operacional gerado e a query API
type-safe do RFC-004:
- CRUD por identificador;
- filtros compostos e predicados de `NULL`;
- ordenação e projecções;
- `count`/`exists`;
- offset e cursor pagination;
- SQL preview.

A Fase 5 acrescentou:
- relações 1:1, 1:N e N:N por model de junção, com `include` explícito;
- transacções com savepoints;
- row locks, timeouts e streaming;
- batch, `updateMany`/`deleteMany` e optimistic locking.

Os itens abaixo descrevem o
roadmap e não são todos funcionalidades disponíveis nesta fase.

## Planeamento avançado por fase

**Proposta, sem implementação nesta entrega.** Os nomes 0.1/0.5/1.0 abaixo são
marcos conceptuais, não versões publicadas. O SQL preview, count/exists e os
restantes recursos descritos acima continuam disponíveis no seu subconjunto;
não equivalem ao Inspector, agregações gerais ou suporte JSONB/arrays planeados.

| Capacidade planeada | Janela | Prioridade e dependência principal |
|---|---|---|
| TUPREL Safe Migration Engine MVP | F6 | P0: gates de histórico e contratos específicos de execução; planos, riscos, drift e revisão antes de aplicar |
| Hardening avançado de migrations | F10 | P0: MVP consolidado, matriz de falhas/recuperação e preservação de dados |
| TUPREL Query Inspector core e EXPLAIN | F9 | P1: runtime e contrato de observação; métricas opt-in, SQL gerado e planos estimados limitados |
| Diagnóstico de consultas lentas | F9 | P1: métricas com fronteiras temporais e limites explícitos |
| JSONB e arrays PostgreSQL avançados | F9 | P1: contratos de schema/codegen/binding e compatibilidade com migrations |
| Agregações tipadas além de count/exists | F9 | P1: tipos de resultado, NULL, precisão e query AST aprovados |
| Benchmarks e análise de performance | F10 | P1: protocolo, baseline reproduzível e orçamento aprovado; sem resultados presumidos |
| Contratos públicos para IDE/Studio | F10 | P1: versionamento, redacção e compatibilidade; sem UI no core |
| TUPREL Studio | Pós-1.0 | P2: contratos estáveis, introspecção e motores existentes; aplicação complementar opcional |
| Integração IntelliJ | Pós-1.0 | P2: contratos/compiler/CLI estáveis; plugin opcional, sem parser duplicado |
| Index Advisor baseado em evidência | Pós-1.0 | P2: Inspector, planos/métricas/catálogo e validação antes/depois; sem DDL automático |
| Evolução das interfaces de diagnóstico | Pós-1.0 | P2: experiência real com os contratos da 1.0 e compatibilidade explícita |

Objectivos, limitações, critérios mensuráveis e sequência em
[ADVANCED_DEVELOPER_EXPERIENCE](../../plans/ADVANCED_DEVELOPER_EXPERIENCE.md).
O [plano operacional F6](../../plans/PHASE_6_EXECUTION.md) mantém os estados reais:
PRs draft e CI remota aprovada não substituem validações locais pendentes.
Studio, o visualizador geral de bases de dados e o cliente colaborativo de APIs
são âmbitos distintos; os dois últimos não serão implementados neste repositório.

## Tuprel 0.1 - Foundation preview

- parser e validator básicos de `schema.tuprel`;
- models, scalars, enums, ids, unique e indexes básicos;
- geração de modelos Java e client mínimo;
- PostgreSQL connection/runtime mínimo;
- `create`, `findUnique`, `findMany`, `update`, `delete` básicos;
- SQL sempre parametrizado;
- CLI `validate`, `format`, `generate` (positional paths and `--schema`),
  help/version, safe init and dry-run; see `CLI_EXPERIENCE.md`;
- migration file format v1, IDs, bounded read-only discovery and exact SHA-256;
  offline `migrate create --name <name> [--dry-run]` for comment-only drafts (F6-002A);
  `migrate status --target-schema <name>` with read-only PostgreSQL history and
  integrity/order diagnostics (F6-002B); no diff/physical drift yet;
- offline Java APIs for a limited PostgreSQL DDL grammar, immutable AST and
  canonical preview (F6-003A1), plus conservative risk plans and context-bound
  SHA-256 digests (F6-003A2), without additional CLI commands;
- Java runner PostgreSQL transaccional (F6-003B): aprovação revalidada sob lock,
  histórico/DDL atómicos por migration, prefixo confirmado, OUTCOME_UNKNOWN e
  inspecção read-only numa nova sessão para reconciliação explícita;
  sem retries/reparação automática ou drift completo;
- CLI `migrate deploy` (F6-003C): dry-run read-only sob lock de sessão e
  execução exclusiva de ficheiros existentes com digest revisto, revalidado
  pelo runner; sem gerar SQL a partir do schema ou aprovar unknown/destructive;
- testes em PostgreSQL real;
- nenhum compromisso de compatibilidade binária.

## Tuprel 0.5 - Developer preview

Inclui 0.1 e acrescenta:

- relações 1:1, 1:N e N:N;
- filtros compostos e ordenação;
- select/include explícitos;
- offset e cursor pagination;
- transactions;
- batch operations;
- query timeout;
- SQL preview;
- raw SQL parametrizado;
- migration engine dev/deploy/status;
- history, checksums, migration lock e drift detection;
- `db pull` PostgreSQL;
- Spring Boot starter;
- Gradle integration;
- seed.

## Tuprel 1.0 - Primeira versão estável

Além do anterior:

- contrato de schema versionado;
- API Java pública estabilizada;
- Maven integration;
- JSONB, arrays, UUID e tipos PostgreSQL prioritários;
- optimistic locking;
- streaming com lifecycle seguro;
- locks de linha suportados explicitamente;
- Query Inspector core, EXPLAIN e diagnóstico de consultas lentas (subconjunto F9);
- logging estruturado e integração de observabilidade opt-in, com limites/redacção;
- migration safety avançada para alterações com risco de data loss;
- contratos públicos versionados de diagnóstico/plano para futuras integrações IDE/Studio;
- mensagens de erro e diagnostics de alta qualidade;
- documentação completa e exemplos compiláveis;
- compatibilidade e matriz de testes definida;
- artefactos de release assinados e supply-chain checks.

## Depois de 1.0

Candidatos, não promessas:

- TUPREL Studio, aplicação complementar opcional sobre contratos públicos;
- R2DBC/reactive;
- outros dialectos;
- read replicas/routing;
- schema diff visual avançado;
- integração IntelliJ opcional e evolução do tooling de IDE/LSP;
- Index Advisor baseado em planos, métricas e catálogo, sem criar/remover índices automaticamente;
- evolução compatível das interfaces de diagnóstico;
- cloud features.

Nenhum item desta secção deve contaminar a arquitectura 1.0 sem necessidade comprovada.
