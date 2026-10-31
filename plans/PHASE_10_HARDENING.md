# Fase 10 - Maven, Compatibilidade e Hardening

**Planeada.** Não implementada por esta actualização estratégica. Os gates de
[RELEASE_1_0](RELEASE_1_0.md) mantêm-se; o âmbito aprovado deve estar implementado
e testado ou explicitamente retirado por decisão, não marcado concluído por calendário.

## Entregas avançadas propostas

| Entrega | Prioridade / dependências | Critério de saída |
|---|---|---|
| MIG-SAFE-02 | P0; Safe Migration MVP e catálogo conhecido | Matriz real de concorrência, falhas/commit incerto e cleanup; zero replay cego; dados preservados e recuperação documentada |
| PERF-01 | P1; subconjuntos F6/F9 e protocolo aprovados | Baselines reproduzíveis off/on/sample, ambiente/amostragem/limitações publicados e orçamento de regressão revisto |
| DX-CONTRACT-01 | P1; experiência Inspector/migrations | Contratos públicos de diagnóstico/plano versionados, compatibilidade/redacção/limites testados e grafo sem UI/IDE no core |

Hardening não aprova SQL não transaccional, backfill automático ou promessa de
zero downtime. Novos estados/gramáticas/formatos exigem revisão própria.
Benchmarks não demonstrados não sustentam afirmações de performance.

Os contratos preparam consumidores futuros; não incluem implementação de Studio
ou IntelliJ na 1.0. A aplicação Studio, o plugin opcional e o Index Advisor
permanecem pós-1.0. Ver dependências e critérios mensuráveis no
[plano avançado](ADVANCED_DEVELOPER_EXPERIENCE.md).

## Entregáveis

- Maven plugin e generated source integration;
- API compatibility tooling;
- JavaDoc completa;
- exemplos Gradle e Maven compiláveis;
- compatibility matrix final;
- dependency locks/verification revistos;
- CodeQL/dependency review limpos;
- threat model revisto;
- performance baselines;
- error messages e diagnostics revistos;
- licence/name/Maven coordinates resolvidos antes de publicação.
