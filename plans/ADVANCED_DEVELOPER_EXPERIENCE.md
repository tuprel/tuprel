# Developer experience avançada

**Estado: proposta estratégica para revisão. Nenhuma implementação nesta entrega.**
Base observada: main `6f66ef47ddffb2c9e6d5e302cf2556536eebf472`, em 2026-10-31.
Esta proposta complementa o Master Plan; janelas não são datas
de lançamento nem aprovação de formatos, APIs ou operações de base de dados.

## 1. Visão, fronteiras e prioridades

O TUPREL continua um toolkit Java schema-first: modelos declarativos, Java gerado,
SQL inspeccionável e operações explícitas. A experiência avançada deve ajudar a
compreender consultas e evoluir dados com segurança, sem acrescentar trabalho
invisível às aplicações ou exigir uma interface visual.

- **P0:** integridade, preservação de dados e conclusão segura do migration MVP.
- **P1:** diagnóstico e PostgreSQL avançado nas Fases 9/10, depois das dependências.
- **P2:** aplicações/integrações opcionais pós-1.0, depois de evidência de utilização.

Prioridade não permite saltar fases. Uma capacidade P1 não desbloqueia o executor
de migrations nem dispensa os dois testes locais de permissões do #28.
O [plano operacional F6](PHASE_6_EXECUTION.md) mantém o estado e a ordem das tarefas.

O **Studio** é uma aplicação complementar do TUPREL; não é o visualizador geral
de bases de dados. Esse visualizador e o cliente colaborativo de APIs são produtos
independentes, com roadmaps próprios e sem implementação neste repositório.
Não serão criados módulos, interfaces vazias, serviços cloud ou dependências de UI
para representar antecipadamente esta proposta.

## 2. Mapa de capacidades planeadas

Todas as linhas abaixo estão **PLANEADAS**, mesmo quando reutilizam uma base
já disponível. Código em PR draft não é funcionalidade integrada na main.

| ID | Objectivo | Janela / prioridade | Dependências de início | Limite de âmbito |
|---|---|---|---|---|
| MIG-SAFE-01 | Safe Migration Engine MVP: plano, riscos, execução conservadora, drift e revisão | F6 / P0 | Gates F6-002B e contratos F6-003 aprovados | Subconjunto explícito; sem SQL arbitrário/reset |
| MIG-SAFE-02 | Hardening avançado de migrations e recuperação | F10 / P0 | MIG-SAFE-01 consolidado, catálogo conhecido e matriz de falhas | Sem rollback universal, zero downtime ou extensão tácita de SQL |
| QINS-01 | Query Inspector core: SQL gerado, métricas e correlação | F9 / P1 | Runtime estável; contrato de observabilidade aprovado | Opt-in, sem Studio ou captura de binds por omissão |
| QINS-02 | Inspecção explícita de planos PostgreSQL | F9 / P1 | QINS-01; adapter/permissões/redacção aprovados | EXPLAIN estimado e limitado; sem ANALYZE automático |
| SLOW-01 | Distinguir operações lentas e as suas componentes | F9 / P1 | QINS-01; fronteiras temporais definidas | Não equiparar tempo da aplicação a tempo do servidor |
| PG-JSON-01 | JSONB com representação, binding e operadores tipados | F9 / P1 | RFC de tipos; schema/codegen/runtime existentes | Subconjunto definido; Json actual não equivale a este suporte |
| PG-ARRAY-01 | Arrays PostgreSQL tipados e lifecycle JDBC correcto | F9 / P1 | RFC de tipos/cardinalidade e binding | Listas de relação não se tornam arrays SQL implicitamente |
| AGG-01 | Agregações tipadas e agrupamentos | F9 / P1 | RFC de resultados, NULL, precisão e query AST | Estender count/exists existentes, sem SQL por concatenação |
| PERF-01 | Medir overhead e custos com baselines reproduzíveis | F10 / P1 | Subconjuntos F6/F9 testados; protocolo de medição aprovado | Nenhum ganho de desempenho presumido |
| DX-CONTRACT-01 | Contratos públicos estáveis para IDE/Studio | F10 / P1 | Experiência com QINS/MIG; compatibilidade e segurança | Sem UI, plugin ou protocolo remoto implementados agora |
| STUDIO-01 | Exploração visual local de modelos, relações, índices e migrations | Pós-1.0 / P2 | DX-CONTRACT-01, introspecção F8 e subconjuntos estáveis | Aplicação opcional, read-only por omissão |
| STUDIO-02 | Vistas de Inspector e Safe Migration Engine | Pós-1.0 / P2 | STUDIO-01, QINS-02 e MIG-SAFE-02 | Consome os motores existentes, sem executor duplicado |
| IDE-01 | Integração IntelliJ opcional | Pós-1.0 / P2 | DX-CONTRACT-01, CLI/compiler estáveis | Sem parser paralelo nem dependência IntelliJ no toolkit |
| ADV-INDEX-01 | Index Advisor baseado em evidência | Pós-1.0 / P2 | QINS-02, SLOW-01, PERF-01 e catálogo suportado | Recomenda; nunca cria/remove índices automaticamente |

## 3. Safe Migration Engine

### MVP da Fase 6

MIG-SAFE-01 é o nome de produto para as entregas existentes da F6, não um segundo
motor. Reutiliza ficheiros v1/SHA-256, histórico, adapter separado e plano/runner
aprovados nas suas entregas. Deploy só aplica artefactos existentes e revistos.
Planos/desconhecidos não são declarados seguros por uma regex ou por um checksum.

Revisão deve apresentar preview determinístico, riscos de dados/locks, target,
integridade e cobertura de drift. Drift físico só é verificado para o catálogo
realmente compreendido; o estado não verificado/desconhecido permanece explícito.
Status não cria histórico por conveniência. Não há reset ou reparação implícita.

A aprovação liga-se aos artefactos, versões e estado **efectivamente observado**.
Mudanças nesses campos invalidam o vínculo; não prometer detectar toda a alteração
de dados, catálogo externo ou servidor que o modelo não observa. Um digest não
atesta revisão humana, não é assinatura nem token de uso único. O runner deve
reobservar/revalidar sob lock, sem confiar na snapshot fornecida pelo chamador.

Os rótulos de risco do anexo não criam estados públicos novos: risco, disposição
do plano e resultado de execução são conceitos diferentes. A taxonomia final
pertence aos contratos F6-003 em revisão. Não existe estado SAFE implícito.

### Hardening da Fase 10

MIG-SAFE-02 aprofunda concorrência, perda de ligação, falha de commit/rollback/
cleanup, sucesso parcial e recuperação explícita. Deve identificar custos de
locks/reescritas e documentar estratégias expand/backfill/contract com limites.
Orientação não implica execução automática desses passos ou DDL não transaccional.
Ampliar gramática, histórico ou confirmação exige aprovação e versionamento próprios.

Não prometer zero downtime, recuperação universal de dados ou que um rollback
cancela efeitos externos. Backups e compatibilidade da aplicação continuam a
exigir um plano operacional revisto. Nenhum dado do playground será resetado.

## 4. Query Inspector e consultas lentas

QINS-01 começa por contratos de biblioteca e apresentação CLI, sem comando fictício
no help. Reutiliza SQL preview, query model e lifecycle existentes. Propõe:

- identidade estrutural da consulta e correlação por operação/statement;
- SQL gerado com placeholders, sem valores, URL ou credenciais;
- duração monotónica, contagens conhecidas de linhas/chamadas e falhas categorizadas;
- métricas opt-in, limites de memória/cardinalidade e retenção explícitos.

Uma operação com includes executa vários statements; os contadores não podem
confundir o total da operação com cada statement. Streams devem distinguir tempo
até à primeira linha, consumo parcial e fecho; não inventar total de linhas.
Aquisição de connection, execução JDBC, fetch/mapping e trabalho do consumidor
são componentes distintas quando observáveis. Ausência de medida não é zero.

SLOW-01 aplica um limiar configurável, positivo e finito à componente documentada.
Classificação inicial é diagnóstico, não optimização automática. EXPLAIN não é
disparado em todas as consultas lentas. Falhas do observador não podem alterar o
commit/resultado original, bloquear indefinidamente a aplicação ou provocar retries.
Medir overhead off/on desde o primeiro protótipo; os orçamentos devem ser revistos
antes de assumir um contrato de produção. A baseline formal da F10 não permite
declarar custo zero ou estabilidade de desempenho antecipadamente.

QINS-02 oferece EXPLAIN explícito e limitado para o subconjunto autorizado de
consultas geradas, com contexto do plano estimado e sem ANALYZE automático.
Custos estimados não são milissegundos. Planeamento também pode avaliar funções;
read-only e ausência de ANALYZE não constituem sandbox para SQL arbitrário.
Ficam por aprovar permissões, tipos/funções admissíveis, tempo, tamanho e profundidade.

Planos podem incluir literais/condições sensíveis mesmo quando o SQL usa binds.
Uma allowlist estrutural de campos exportáveis e testes de redacção devem preceder
a exposição; até lá, omitir o plano bruto. Hash de SQL raw não garante anonimização.
ANALYZE executa a consulta e exige desenho/aprovação separados, nunca uma opção
activada automaticamente ou um conselho para executar em produção.

pg_stat_statements é uma integração posterior opcional: apenas se já disponível
com permissões e escopo adequados. Não instalar extensões, elevar privilégios,
consultar outros tenants por defeito ou assumir que texto SQL da extensão é seguro.

## 5. PostgreSQL avançado e agregações

PG-JSON-01 define representação/codec, validação, limites, SQL NULL versus JSON
null, precisão e operadores suportados. Reutiliza o portador gerado quando fizer
sentido; qualquer biblioteca JSON necessita de ADR, licença e análise de peso.
JSONB pode normalizar o texto: round-trip deve verificar semântica, não prometer
preservação byte a byte. Paths/chaves/valores são inputs hostis e não SQL concatenado.

PG-ARRAY-01 define tipos de elementos, arrays vazios, NULL do array/elemento,
dimensões e bounds. Um primeiro subconjunto pode recusar formas não suportadas;
nunca achatar dimensões ou reinterpretar listas de relações silenciosamente.
Testar binding/mapping e libertação de recursos JDBC mesmo em erro/cancelamento.

AGG-01 estende o AST e tipos da query API: sum/avg/min/max, group by e HAVING
apenas no subconjunto aprovado. Fixar tipos de resultado, precisão, overflow e
nulabilidade segundo PostgreSQL, não por suposição de que entrada e saída têm
o mesmo tipo. Count já existe; não o apresentar como entrega nova.

Estas capacidades exigem revisão coordenada de schema, codegen, renderer/binding,
migrations e catálogo/diff/drift. JSONB/arrays não entram automaticamente na
gramática DDL limitada da F6; alargar essa cobertura exige contrato/versionamento.
Um subconjunto sem suporte em migrations terá de declarar a limitação explicitamente.

## 6. Arquitectura proposta e consumidores opcionais

A direcção actual de dependências mantém-se. As responsabilidades futuras são:

| Responsabilidade | Localização lógica / fronteira |
|---|---|
| Modelos, tipos e SQL estrutural | Schema/compiler, codegen e query model existentes |
| Captura de lifecycle | Fronteiras explícitas do runtime, depois de contrato aprovado |
| Diagnóstico/plano e limites | Contratos tipados, sem depender de uma UI ou IDE |
| Catálogo, EXPLAIN e binding PostgreSQL | Adapter/dialecto; não contaminar domínio puro |
| Orquestração terminal | CLI consome serviços; não duplica motores |
| Studio/IntelliJ | Aplicações/adapters opcionais que consomem contratos estáveis |

Não se fixa agora um novo grafo de módulos, formato de transporte ou tecnologia
de frontend. Um ADR futuro deverá decidir a colocação dos contratos de observação
e garantir ausência de ciclos, transitividade de UI/IDE e dependência obrigatória
de integração. Java 21 e ausência de JPA/Hibernate no core mantêm-se.

DX-CONTRACT-01 propõe versionamento e compatibilidade dos resultados, limites,
redacção e capacidades negociadas. Consumidores não devem fazer scraping de logs
humanos nem serialização Java arbitrária. Fixtures de compatibilidade pertencem
à futura implementação, não a interfaces vazias criadas nesta proposta.

Studio começa local, opcional e read-only, limitado a capacidades realmente
suportadas pelo toolkit/introspecção. Não possui runner próprio nem coloca
credenciais no frontend. Loopback não substitui autenticação: origens/Host,
sessões, CSRF, armazenamento de segredos e permissões precisam de threat model.
Qualquer escrita posterior requer revisão e os mesmos gates do engine, sem atalhos.

IntelliJ reutiliza parser/validator/CLI e ownership da geração; não copia a
linguagem nem modifica builds silenciosamente. Matriz IDE/JDK e distribuição
do plugin são decisões posteriores, sem alterar a baseline do toolkit.

## 7. Index Advisor e desempenho fundamentado

ADV-INDEX-01 requer amostra identificada, janela temporal, versões/configuração
relevante, planos, métricas e catálogo compreendido. Distingue observação,
estimativa, hipótese e resultado medido. Considera índices existentes, selectividade,
custos de escrita, espaço e locks; um sequential scan isolado não prova falta de índice.

Recomendação deve explicar evidência, confiança/limites, alternativas e protocolo
antes/depois em dados sintéticos representativos. Sem evidência suficiente, emitir
«insuficiente», não um índice inventado. Não aplicar DDL, remover índices ou afirmar
melhoria garantida. Uma proposta aceite segue o fluxo revisto de migrations.

PERF-01 mede cenários equivalentes com observação desligada/ligada/amostrada,
incluindo throughput, distribuição de latência, alocações, memória, binds e número
de statements. Publicar ambiente, versões, dataset sintético, aquecimento,
concorrência, número de amostras e incerteza. Comparações com JDBC usam o mesmo
SQL/trabalho; percentis só são interpretados com amostragem justificável.
Orçamentos de overhead e tolerâncias de regressão são aprovados com o protocolo
antes de servirem de gate. Não existem benchmarks ou resultados desta entrega.

## 8. Critérios de aceitação propostos

São **testes futuros**, não resultados aprovados. A implementação de cada fatia
deve fixar o seu subconjunto e passar integralmente a matriz correspondente.

| Capacidade | Critério mensurável e evidência exigida |
|---|---|
| MIG-SAFE-01 | Matriz cobre ficheiro/target/estado/versionamento alterados, SQL desconhecido/destrutivo, drift conhecido/desconhecido, rollback, concorrência e falha de commit; zero execução de planos bloqueados ou não aprovados |
| MIG-SAFE-02 | Fault injection antes/durante/depois de commit e cleanup distingue prefixo confirmado/rollback/OUTCOME_UNKNOWN; zero replay automático; zero alterações fora de schemas isolados; recuperação documentada e testada |
| QINS-01 | Modo desligado não exporta eventos nem cria ficheiros/threads do Inspector; com limites de teste N=128 fingerprints/Q=256 eventos, retenção nunca excede limites e descartes são contabilizados; zero sentinelas secretas em saída |
| QINS-02 | Timeouts/cancelamento, plano malformado/sobredimensionado, permissões insuficientes e literais sensíveis cobertos; zero ANALYZE automático, zero plano bruto não aprovado e zero alteração de privilégios |
| SLOW-01 | Relógio controlado com limiar de teste 10 ms: 9 não sinaliza, 10/11 sinalizam exactamente uma vez; separar aquisição/execução/consumo, inclusive erro, cancelamento e stream parcial |
| PG-JSON-01 | Round-trip semântico de objectos/arrays/escalares, SQL NULL/JSON null, Unicode, precisão e limites; inputs inválidos/hostis rejeitados; operadores mantêm valores bound |
| PG-ARRAY-01 | Matriz vazio/NULL/elemento NULL, tipos, dimensões/bounds suportados e não suportados; zero perda silenciosa e recursos JDBC fechados nos caminhos de falha |
| AGG-01 | Compilação positiva/negativa de tipos/modelos/agrupamento; count vazio=0 e NULL dos outros agregados representado; precisão/overflow e grupos/HAVING conferidos em PostgreSQL real |
| PERF-01 | Protocolo e orçamento aprovados, artefactos reproduzíveis off/on/sample e comparação com baseline; relatório inclui limitações, não resultados seleccionados apenas por serem favoráveis |
| DX-CONTRACT-01 | Fixtures de consumidor compatível/incompatível, campos/capacidades desconhecidos, redacção e limites; grafo sem UI/IDE nos artefactos do core; baseline Java 21 preservada |
| STUDIO-01/02 | Read-only imposto no serviço, target/permissões explícitos, rejeição de origem/sessão inválida e zero segredos no browser; mesma aprovação/revalidação do engine para escrita futura |
| IDE-01 | Diagnóstico/generation reproduzem compiler/CLI; recusa de overwrite preservada; matriz de IDEs declarada e toolkit compila/executa sem plugin |
| ADV-INDEX-01 | Casos com índice existente/evidência insuficiente não inventam recomendação; cada sugestão tem proveniência, custos e comparação medida; zero CREATE/DROP automático |

N/Q/10 ms são parâmetros de fixtures propostas, não defaults de produção ou
medições de desempenho. Limites, orçamentos e APIs finais exigem aprovação própria.

## 9. Sequência incremental e fronteiras de release

1. **Agora:** rever esta documentação; concluir os gates já existentes da F6.
   #28 mantém draft, dois testes locais e build completa pendentes até confirmação
   da criação manual da role. #30/#31 continuam propostas; não começar executor.
2. **MVP F6:** fechar contratos específicos, runner/deploy conservador, diff,
   drift/dev do subconjunto e consolidação. Playground só numa tarefa de baseline
   própria, sem apagar dados. Não acrescentar Inspector/Studio à F6.
3. **F7/F8:** manter integrações e introspecção do roadmap; reutilizar as suas
   capacidades em vez de introduzir leitores de catálogo paralelos na UI.
4. **F9:** RFCs de observação/tipos; QINS-01, depois SLOW-01 e QINS-02 em fatias
   separadas. JSONB, arrays e agregações têm entregas/testes próprios, com
   compatibilidade de migrations decidida antes de anunciar cobertura conjunta.
5. **F10/1.0:** MIG-SAFE-02, PERF-01 e DX-CONTRACT-01, compatibilidade e gates de
   release. A release inclui apenas o subconjunto aprovado, implementado/testado
   ou explicitamente retirado do âmbito; não reduz gates para cumprir uma data.
6. **Pós-1.0:** Studio read-only e IntelliJ opcionais; Advisor só após evidência
   e medição. Evoluir interfaces com experiência real, sem tornar UI requisito
   para utilizar, testar ou publicar a biblioteca.

## 10. Contratos existentes e compatibilidade documental

| Referência | Reutilização / decisão que continua necessária |
|---|---|
| ADRs 0002/0003/0004/0005/0006 e MODULE_BOUNDARIES | Java 21, PostgreSQL, schema-first, acesso explícito, binding e grafo; sem mudança funcional nesta proposta |
| RFCs 001/002/004/005 | Frontend, geração, query AST e lifecycle existentes; tipos/agregados/observação precisam de extensões aprovadas, não novas implementações paralelas |
| RFC-006 e ADRs 0012/0013/0014 | Um só migration engine; aprovação arquitectural de 0013/0014 registada no trabalho #28 não equivale a executor autorizado; estados dos ficheiros aguardam essa integração |
| PHASE_6_EXECUTION e propostas 0015/0016/0017 em #30/#31 | Gramática, digest/preview e execução mantêm aprovação específica; esta estratégia não os aceita nem altera |
| Product specification, CLI experience, fases 8/9/10 e RELEASE_1_0 | Reutilizar contratos e separar visão/calendário de disponibilidade; não anunciar comandos planeados no help |

A visão contém milestones conceptuais anteriores, incluindo Studio na 1.0.
Para calendarização avançada, a presente proposta e o Master Plan colocam Studio,
IntelliJ e Advisor no pós-1.0. Não reescrever exemplos da visão como APIs entregues.

O PR #28 também altera MASTER_PLAN e SUPPORTED_FEATURES. Esta branch não recebe código
nem modifica o HEAD do #28. Antes de qualquer integração, comparar ambos os diffs
e ensaiar a junção apenas dos documentos: preservar o roadmap estratégico e os
gates/status reais da F6, sem marcar status como disponível antes da integração
validada do #28. Repetir a verificação se qualquer HEAD mudar. Evidência e resolução
da sobreposição acompanham o PR; não confundir ensaio documental com merge real.

Ensaio efectuado sobre a main indicada no início, HEAD do #28
`9448e0dd36cc77021d541747f1b747618abc0735` e base comum
`618756da423099ad77878a5891c863dc52a6311e`: MASTER_PLAN e SUPPORTED_FEATURES
combinam sem conflitos nas duas ordens. As saídas são idênticas por documento,
preservam os épicos novos e os parágrafos de status do #28. Os trechos das Fases
0–5 e das funcionalidades implementadas também foram conferidos sem alterações.
Foram usadas apenas cópias temporárias e git merge-file, sem merge, alteração de
index/branch do #28 ou integração do seu código nesta proposta.

## Referências

- [Master Plan](MASTER_PLAN.md), [Fase 6 operacional](PHASE_6_EXECUTION.md),
  [Fase 9](PHASE_9_ADVANCED_POSTGRESQL.md), [Fase 10](PHASE_10_HARDENING.md)
  e [release gate](RELEASE_1_0.md).
- [Fronteiras](../docs/architecture/MODULE_BOUNDARIES.md),
  [visão](../docs/product/TUPREL_ORM_SPEC.md), [matriz](../docs/product/SUPPORTED_FEATURES.md)
  e [CLI](../docs/product/CLI_EXPERIENCE.md).
- [RFC-004](../docs/rfcs/RFC-004-type-safe-query-api.md),
  [RFC-006](../docs/rfcs/RFC-006-migration-engine-proposal.md),
  [ADR-0012](../docs/adr/ADR-0012-offline-cli-and-migration-files.md),
  [ADR-0013](../docs/adr/ADR-0013-postgresql-migration-adapter.md)
  e [ADR-0014](../docs/adr/ADR-0014-migration-history-and-execution-safety.md).
- PostgreSQL 17: [EXPLAIN](https://www.postgresql.org/docs/17/sql-explain.html),
  [avaliação de funções](https://www.postgresql.org/docs/17/xfunc-volatility.html)
  e [semântica de agregações](https://www.postgresql.org/docs/17/functions-aggregate.html).
