# Master Plan

O desenvolvimento avança por gates. Não saltar fases porque uma funcionalidade futura parece interessante.

O [plano de developer experience avançada](ADVANCED_DEVELOPER_EXPERIENCE.md)
detalha objectivos, dependências, prioridades e critérios de aceitação das
capacidades futuras. É uma proposta estratégica: não aprova contratos novos,
não autoriza implementação nesta entrega e não transforma backlog em trabalho concluído.

## Fase 0 - Fundação de engenharia

**Concluída.** Build preparado para futuros módulos, quality gates,
CI configurada, security baseline, test infrastructure, package naming
provisório e documentação operacional. Nenhum módulo de produto é criado sem
código real.

## Fase 1 - Schema language

**Concluída.** RFC-001 foi aceite para o subconjunto inicial. `tuprel-schema`
fornece lexer/parser, AST, diagnostics, model/enums/scalars, atributos
fundamentais, formatter e validator; `tuprel-cli` expõe `validate` e `format`.

## Fase 2 - Java code generation

**Concluída.** RFC-002 aceite para o subconjunto implementado.
`tuprel-codegen-java` gera model values, enums, metadata e inputs a partir do
schema validado, com output determinístico, escrita segura e compilation tests.
`tuprel generate` expõe a geração.

## Fase 3 - Runtime PostgreSQL mínimo

**Concluída para o subconjunto do plano.** `tuprel-sql`, `tuprel-runtime` e
`tuprel-postgresql` fornecem CRUD estrutural mínimo, DataSource/JDBC lifecycle,
SQL + binds, mapping tipado, erros e integração PostgreSQL real. O cliente
operacional gerado e a query API permanecem nas fases seguintes.

## Fase 4 - Query API type-safe

**Concluída para o subconjunto do plano.** O RFC-004 e o ADR-0010 definem o
cliente operacional gerado sobre o runtime da Fase 3. Inclui filtros tipados
por model, composição lógica, predicados de `NULL`, ordering, projecções,
`count`/`exists`, offset e cursor pagination determinística, e SQL preview.
Relações, `include` e transacções ficam para a Fase 5.

## Fase 5 - Relações e transacções

**Concluída para o subconjunto do plano.** O RFC-003, o RFC-005 e o ADR-0011
definem:
- 1:1, 1:N e N:N através de um model de junção, com `include` explícito e
  carregamento em lote sem N+1;
- transacções explícitas com savepoints, isolamento, read-only e prazo;
- row locks, timeouts de query e streaming;
- `createMany`, `updateMany` e `deleteMany`;
- optimistic locking com `@version`.

A relação N:N implícita espera pelas migrations.

## Fase 6 - Migration engine

Execução contínua, dependências e gates em [PHASE_6_EXECUTION](PHASE_6_EXECUTION.md).

**F6-001/F6-002A/F6-002B integradas.** Fundação CLI,
ficheiros puros e criação offline de drafts, conforme RFC-006 e ADR-0012.
Ver [contrato F6-002A](F6-002A_MIGRATION_CREATE.md) e
[status F6-002B](F6-002B_MIGRATION_STATUS.md). ADRs 0012/0013/0014 aceites;
histórico/status read-only disponíveis. F6-003A1 integrada no #30: gramática,
AST e preview DDL offline. F6-003A2 integrada no #31: planos/riscos/digests
offline. F6-003B1–B4 e F6-003C integradas nos PRs #38–#42: runner transaccional,
locks, outcomes incertos e CLI deploy com aprovação contextual. Diff/drift/dev
continuam incrementais; reset fica fora do MVP.

A aprovação arquitectural dos ADRs 0013/0014 está registada no trabalho do
[PR #28](https://github.com/tuprel/tuprel/pull/28), mas não autoriza o executor.
Histórico/status integrado no #28 após os testes locais de permissões e build
completa. Resultados/CI e estado corrente seguem PHASE_6_EXECUTION.
ADRs 0015/0016/0017 aprovados em 2026-10-31; #28/#30/#31 integrados após
testes/revisão e CI dos HEADs actuais, com CI/CodeQL main aprovados após cada merge.
O executor do subconjunto aceite está implementado. F6-004A integrada no #44:
comparação lógica offline do AST existente, sem escrita, conforme
[ADR-0018 aceite](../docs/adr/ADR-0018-logical-schema-diff.md).
[ADR-0019 aceite](../docs/adr/ADR-0019-schema-projection-and-offline-diff.md)
define a projecção de schema.tuprel e a futura CLI diff. Catálogo/drift/dev seguem
em fatias posteriores com contratos próprios; não são funcionalidades disponíveis.

**Objectivo planeado — TUPREL Safe Migration Engine MVP (P0):** artefactos
existentes e revistos, plano/preview determinístico, integridade, riscos,
drift no catálogo conhecido e revisão vinculada ao estado antes da execução.
O subconjunto aprovado deve proteger dados, bloquear desconhecidos/destrutivos,
confirmar DDL e histórico na mesma transacção no subconjunto suportado e comunicar resultado
incerto sem retries cegos. Diff/drift/dev continuam nas entregas F6-004/F6-005;
consolidação na F6-006. Sem reset, SQL arbitrário, rollback universal ou garantia
de zero downtime. Critérios em MIG-SAFE-01 do plano avançado; sequência e estado
operacional continuam exclusivamente em PHASE_6_EXECUTION, sem antecipar a Fase 9.

## Fase 7 - Developer integrations

CLI madura, Spring Boot starter, Gradle plugin, seed e integração de generated sources.

## Fase 8 - Introspection e db pull

Catálogos PostgreSQL -> internal schema model -> `schema.tuprel`, com naming e round-trip tests.

## Fase 9 - PostgreSQL avançado e diagnostics

**Planeada, prioridade P1 após os gates anteriores.** TUPREL Query Inspector core
reutiliza o SQL preview existente e acrescenta captura opt-in, métricas limitadas,
correlação e diagnóstico de consultas lentas. EXPLAIN é uma operação explícita,
com permissões, limites e redacção; ANALYZE fica fora do comportamento automático.

JSONB, arrays PostgreSQL e agregações tipadas além de count/exists evoluem o
schema/codegen/query model existentes, sem uma DSL paralela. As extensões têm de
ser coerentes com renderer/binding, migrations e catálogo conhecido; não confundir
Json/listas do frontend com suporte de runtime já entregue. Dependências e testes
em QINS-01/02, SLOW-01, PG-JSON-01, PG-ARRAY-01 e AGG-01 do plano avançado e em
[PHASE_9_ADVANCED_POSTGRESQL](PHASE_9_ADVANCED_POSTGRESQL.md).

## Fase 10 - Maven, compatibilidade e hardening 1.0

**Planeada.** Maven plugin, API compatibility, documentação completa,
security hardening e supply-chain/release pipeline mantêm-se no âmbito.
MIG-SAFE-02 (P0) aprofunda falhas, concorrência, preservação de dados e recuperação
do subconjunto de migrations; não alarga silenciosamente a gramática aprovada.
PERF-01 (P1) produz benchmarks reproduzíveis e análise de custos com evidência,
sem vantagens de desempenho presumidas.

DX-CONTRACT-01 (P1) estabiliza os contratos públicos de diagnóstico/plano para
futuros consumidores IDE e Studio: versionamento, compatibilidade, redacção e
teste de dependências sem UI no core. Não inclui uma aplicação visual nem plugin.
Ver [PHASE_10_HARDENING](PHASE_10_HARDENING.md) e o plano avançado. A 1.0 exige
os critérios do âmbito aprovado; reduzir esse âmbito requer decisão explícita.

## Fase 11 - Release candidate

Freeze de API, migration format, schema language version, RC testing e critérios em `RELEASE_1_0.md`.

## Pós 1.0

**Planeado, prioridade P2; sem data de entrega:**

- **TUPREL Studio:** aplicação complementar opcional que consome contratos
  públicos do toolkit, começa read-only e reutiliza Inspector/migrations;
- **IntelliJ:** integração opcional, sem parser duplicado nem dependência de IDE no core;
- **Index Advisor:** recomendações de índices fundamentadas em planos, métricas
  e catálogo, com hipótese, limites e validação antes/depois; sem DDL automático;
- evolução das interfaces de diagnóstico depois de experiência real com os
  contratos da 1.0. Reactive/R2DBC e novos dialectos continuam candidatos separados.

Studio e IntelliJ dependem de DX-CONTRACT-01; Advisor depende de Inspector,
evidência suficiente e catálogo suportado. O visualizador geral de bases de dados
e o cliente colaborativo de APIs são produtos independentes, fora deste repositório.
Nada desta janela bloqueia a conclusão da Fase 6 ou introduz dependências de UI
na arquitectura Java schema-first. Cada implementação exige RFC/ADR e gates próprios.
