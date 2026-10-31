# Fase 9 - PostgreSQL Avançado e Diagnostics

**Planeada.** Prioridade P1, depois dos gates das fases anteriores. Não há código
novo desta fase nesta proposta. Objectivos, dependências e critérios detalhados
em [ADVANCED_DEVELOPER_EXPERIENCE](ADVANCED_DEVELOPER_EXPERIENCE.md).

## Entregas propostas

| Entrega | Objectivo / dependência | Gate de aceitação |
|---|---|---|
| QINS-01 | Query Inspector core sobre runtime/query model existentes | Opt-in, contadores/durações fiáveis, limites e redacção; observer não altera resultado da operação |
| SLOW-01 | Diagnóstico de consultas lentas depois de QINS-01 | Limiar testável com relógio controlado; aquisição, execução e consumo não confundidos |
| QINS-02 | EXPLAIN explícito depois de QINS-01 e contrato PG | Planos estimados, permissões/timeouts/tamanho; sem ANALYZE automático ou exposição de condições sensíveis |
| PG-JSON-01 | JSONB no schema/codegen/renderer/binding existentes | Round-trip semântico, SQL NULL/JSON null, precisão, limites e operadores parametrizados |
| PG-ARRAY-01 | Arrays SQL com contrato de tipos/cardinalidade | Vazio/NULL/elementos, bounds/dimensões e recursos JDBC testados; relações não reinterpretadas |
| AGG-01 | Agregações tipadas e group by/HAVING | Tipos/resultados/NULL/overflow e rejeição em compilação de combinações inválidas |

Tipos e agregações têm RFCs/fatias separados, sem DSL ou parsers paralelos.
As extensões precisam de compatibilidade explícita com gramática de migrations,
catálogo e drift; suporte no runtime não implica cobertura automática do engine.
Não refazer count/exists, UUID, preview ou timeouts já disponíveis no subconjunto
actual. Não prometer implementação de todo o backlog apenas por constar desta fase.

## Funcionalidades candidatas para 1.0

- UUID;
- JSONB;
- arrays;
- enums/enum mapping decidido;
- decimal/numeric rigoroso;
- temporal types prioritários;
- raw SQL parametrizado;
- query preview;
- EXPLAIN estimado e limitado; ANALYZE apenas com desenho/aprovação específicos;
- structured query logging;
- metrics/tracing integration;
- cancellation e timeouts robustos;
- medições iniciais de hot paths, formalizadas como baseline na Fase 10.

Cada tipo deve ter testes de round-trip e null behavior.
Critérios no plano avançado são testes futuros, não resultados executados.
Studio, plugin IntelliJ e Index Advisor não fazem parte da implementação F9.
