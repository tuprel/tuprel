# Fase 6 — Plano de execução contínua

Plano operacional único. A autorização do utilizador permite implementação
sequencial e integração dos PRs abrangidos **apenas** depois de cumprir todos
os gates abaixo. Não autoriza operações em produção nem decisões críticas
ainda pendentes. Java 21, PostgreSQL primeiro, CONTRIBUTING.md obrigatório.

## Estado observado

Em 2026-10-31 o utilizador confirmou a execução de STATUS_TEST_ROLE.sql na
tuprel_devtest e aprovou explicitamente os ADRs 0015, 0016 e 0017. Os dois
testes locais de permissões passaram depois dessa confirmação (2 testes,
0 falhas/skips). A build completa do #28 passou: 266 testes aprovados,
4 symlinks ignorados por privilégio Windows; os quatro casos continuam
obrigatórios na CI Linux. Integrações seguem #28 → #30 → #31, com nova CI no
HEAD de cada PR e da main. Não implementar executor nesta tarefa de integração.

Estado funcional integrado em `1f36f2c55883711389b030dd87dd06fb95f36fab` após #31,
verificado em 2026-10-31. CI e CodeQL aprovados após cada merge de #28, #30 e
#31, sem resultados CodeQL. Os quatro checks de cada PR passaram no HEAD
revisto antes de ready/merge; nenhuma protecção foi contornada.
#35 e o roadmap #34 foram integrados pelo proprietário durante
a preparação. A apresentação, exemplos, navegação e política são preservados.

Estados: TODO, IN_PROGRESS, BLOCKED, TESTING, MERGED. TESTING inclui revisão e
checks em curso; BLOCKED identifica o gate externo que impede a integração.
Branch/PR/SHA só são preenchidos quando existem; não confundir código de um
PR com código integrado na main. Datas/HEADs acima são evidência da observação,
não substituem nova consulta antes de cada integração.

| Funcionalidade | Estado | Dependências | Branch / PR | Commit integrado | Problema pendente |
|---|---|---|---|---|---|
| F6-001 CLI e formato v1 | MERGED | ADR-0012 aceite | feat/f6-001-cli-migration-foundation / #26 | bf057d50c086811ae0602d46aa8db6d8d1026868 | — |
| F6-002A create offline | MERGED | F6-001 | feat/f6-002a-migration-create / #27 | 9a6b4f872bc020634d9b8dee4ad6f56c2b5db938 | — |
| F6-002B histórico/status | MERGED | ADRs 0013/0014 aceites; role/testes confirmados | feat/f6-002b-migration-status / [#28](https://github.com/tuprel/tuprel/pull/28) | 894b408ddee6bfd98ad158c656f3909cf045d5b3 | — |
| F6-003A1 gramática/AST/preview offline | MERGED | #28 integrado; ADR-0015 aprovado | feat/f6-003a-sql-grammar / [#30](https://github.com/tuprel/tuprel/pull/30) | fa9ebbfac408b18a5da046cd447cc9d1bfe6a6a8 | — |
| F6-003A2 plano, riscos e digest offline | MERGED | A1 integrado; ADRs 0016/0017 aprovados | feat/f6-003a-plan-digest / [#31](https://github.com/tuprel/tuprel/pull/31) | 1f36f2c55883711389b030dd87dd06fb95f36fab | — |
| F6-003B runner transaccional | TODO | #28 e A1/A2 integrados; ADRs 0015/0016/0017 aprovados | — | — | Entrega futura; não iniciada na tarefa de integração |
| F6-003C CLI deploy | TODO | Runner aprovado/testado | — | — | Aprovação vinculada ao estado sob lock |
| F6-004 diff/preview | TODO | Deploy seguro; contrato de comparação | — | — | Modelo de diferenças/desconhecidos |
| F6-005 drift/dev | TODO | Diff e catálogo físico conhecido | — | — | Catálogo parcial, drift e confirmação revalidada |
| F6-006 consolidação | TODO | Subconjuntos anteriores integrados | — | — | Matriz, segurança, documentação e desempenho medido |
| F6-006 playground baseline | TODO | Motor estável; tarefa isolada com plano de preservação | — | — | Inventário read-only e consentimento antes de adoptar migrations |
| F6-006 ignore de create.lock | TODO | Contrato de init/ownership | — | — | Evitar inclusão acidental sem sobrescrever .gitignore |

## Critérios e testes por entrega

### F6-002B

Bloqueio administrativo resolvido por confirmação explícita do utilizador;
sem outras credenciais nem alterações administrativas. Executados/aprovados:

- HistoryIntegrationTest.selectOnlyRoleWorksAndCannotWrite;
- HistoryIntegrationTest.missingUsageAndSelectPrivilegesAreExplicitErrors.

Validação anterior: 264 testes locais aprovados + 2 do playground; quatro skips de
symlink Windows executados/aprovados na CI Linux; 12 testes locais independentes
do histórico; 25 testes PG existentes; launcher real; JavaDoc/Spotless/build-logic.
CI completa aprovada em container isolado, incluindo privilégios. Não substitui
os dois casos locais. Primeiras tentativas falharam no setup por role inexistente.
Ver [evidência e comandos do #28](F6-002B_MIGRATION_STATUS.md).

Após a confirmação, os dois testes e a build raiz sem filtros passaram
(266 aprovados, 4 skips Windows justificados). Build/checkConnection do
playground passou depois da raiz (dois testes, PostgreSQL 17.11, porta 5432),
sem run/bootstrap. Rever diff/segredos,
actualizar checks no HEAD actual, marcar ready, integrar se todos os gates
passarem e validar main antes de qualquer integração seguinte.

### F6-003A1 e A2

Divisão para evitar um PR grande e permitir revisão separada da gramática e
do vínculo de aprovação. Código offline para validar a proposta, não autorização
para executar DDL. Reutilizar MigrationFile/Id/Checksum, modelos de histórico e
SqlIdentifier. Não recriar parser de schema, loader dotenv ou validador de paths.

A1: gramática explícita do subconjunto, lexer/parser completo para esse
subconjunto, AST tipado e preview determinístico; toda a entrada consumida,
sem regex/split por ponto e vírgula como promessa de segurança SQL. Testes de
round-trip, bytes, comentários, limites, identificadores e entradas adversariais.
DDL PostgreSQL permanece no adapter, sem ciclos ou dependências externas novas.

A2: plano ordenado, riscos e desconhecidos bloqueados, verificação de target e
integridade, digest versionado de artefactos exactos e estado observado. Testes
golden independentes da plataforma, mutação de cada campo, determinismo e
invalidação por mudanças. Digest não prova revisão humana nem drift físico.
Não anunciar comandos ainda não implementados; não abrir ligações JDBC.

Validação inicial da A1 (antes da confirmação da role): 32 testes novos aprovados (0 skips),
suite unitária da raiz aprovada, JavaDoc Werror/Spotless aprovados e build do
playground aprovada depois da raiz. Tarefas sem mudanças reutilizadas pelo
Gradle. Não se executou SQL novo nem testes locais dependentes da role.
Contrato aceite em [ADR-0015](../docs/adr/ADR-0015-postgresql-ddl-subset.md):
nomes quoted, built-ins qualificados, três operações e parser whole-input.
Preview não significa aprovação de execução. ADR-0015 aprovado explicitamente
em 2026-10-31; integração depende de #28, sincronização e nova validação.

Revalidação da A1 após sincronizar #28 e a main pública: build raiz sem filtros
aprovada, **298 testes aprovados e quatro skips Windows justificados**. A task
de testes do adapter foi forçada a executar; os 32 casos da gramática passaram
com zero skips, incluindo entradas adversariais, limites e round-trip. Os 14
testes de histórico, 25 PG anteriores e launcher real passaram. JavaDoc Werror
e Spotless mantidos. Build/checkConnection do playground passou depois da raiz,
reutilizando os seus dois testes inalterados. Exemplo Java do guia compilado
com -Xlint:all -Werror e executado offline; 148 links internos e 31 anchors
README preservados. Sem SQL novo executado, alterações de protocolo ou comandos CLI.

Validação inicial da A2 (antes da confirmação da role): 14 testes novos aprovados (0 skips), incluindo dois
vetores normativos calculados também por referência PowerShell/.NET independente,
mutação dos campos, limites de statements/preview e imutabilidade/concorrência.
Suite unitária, JavaDoc Werror e Spotless aprovados; build do playground aprovada
depois da raiz. Não se executou SQL novo nem os testes locais de role pendentes.
Contratos aceites [ADR-0016](../docs/adr/ADR-0016-offline-migration-plan-digest.md) e
[ADR-0017](../docs/adr/ADR-0017-transactional-runner-gates.md) explicitam protocolo,
target, limitações de attestation/drift/replay e gates reais do futuro runner.
Nenhum executor ou comando CLI fictício foi implementado.

Revalidação da A2 com #30 sincronizado: **312 testes aprovados, quatro skips de
symlinks Windows justificados**, zero falhas. Task unitária do adapter forçada
a executar: 32 testes de gramática e 14 de planos/digests sem skips. Suite
completa inclui os 14 testes reais de histórico, 25 PG anteriores e launcher.
JavaDoc Werror e Spotless aprovados. Playground build/checkConnection passou
sequencialmente, com os dois testes inalterados reutilizados. Ambos os exemplos
Java do guia compilaram com -Xlint:all -Werror e executaram offline. Os dois
vetores normativos foram recalculados por PowerShell/.NET e coincidiram.
ADRs 0016/0017 registam aceitação; versões/binário/digests preservados. Nenhum
executor, lock de deployment ou SQL de migrations foi executado ou implementado.

ADRs 0015/0016/0017 aprovados explicitamente em 2026-10-31. A aprovação não
substitui dependências, revisão de segurança nem testes/CI no HEAD sincronizado.
Os PRs só saíram de draft depois desses gates. Não executar SQL de migrations
nem iniciar runner na presente tarefa, limitada à integração do trabalho existente.

### F6-003B e C

Contratos específicos aceites; numa entrega futura: connection dedicada, lock de sessão, histórico e
checksums, DDL + registo atómicos por migration, falhas de commit/rollback,
OUTCOME_UNKNOWN e ausência de retries perigosos. Testar concorrência, perda de
ligação, recuperação e recursos em schemas aleatórios da tuprel_devtest; CI
PG isolado. Deploy aplica apenas artefactos existentes revistos, nunca deriva
DDL do schema. Aprovação do plano revalidada sob lock antes de qualquer escrita.

### F6-004, F6-005 e F6-006

Diff determinístico com desconhecidos/perda de dados; drift físico com catálogo
explicitamente suportado; dev com confirmação vinculada e revalidação, sem
reset. Testar estados reais e adversariais. Consolidação inclui Windows/Linux,
macOS quando houver runner, segurança, locks/falhas, integridade, exemplos e
desempenho medido sem inventar benchmarks. Bootstrap do playground só muda em
tarefa própria; não apagar/modificar os dados persistidos para demonstrar o motor.

## Baseline integrada e verificação independente

PR #29 integrado em `618756da423099ad77878a5891c863dc52a6311e`: este plano e
execução dos mesmos workflows em bases `feat/**`, permitindo PRs pequenos
empilhados. Nenhum teste ou quality gate foi desactivado. CI/CodeQL da main
aprovados após a integração.

Validação local dessa baseline em 2026-10-31: build completa da raiz aprovada,
240 testes aprovados e quatro skips de symlink por privilégios Windows. Os
quatro casos permanecem activos na CI Linux. Inclui 25 testes PostgreSQL reais
na tuprel_devtest, sem Docker; tarefas sem alterações reutilizadas pelo Gradle.
Sequencialmente, build do playground (dois testes)
e checkConnection aprovados, PostgreSQL 17.11. Consulta read-only confirmou
4 clientes, 8 produtos, 4 encomendas, 8 itens e zero schemas de testes remanescentes.

Esta baseline não contém o adapter/status do #28 nem os protótipos #30/#31.
A sua build verde não aprova a build local completa dessas branches. Os dois
casos de permissões do #28 estavam por executar nessa baseline. O resultado
posterior, após confirmação da role, consta no estado observado acima.

## Gates de integração e disciplina Git

### Validação final da integração #28 → #30 → #31

Depois do merge do #31, o tree da main foi comparado com o HEAD revisto do PR:
conteúdo idêntico. A build raiz foi repetida com `--rerun-tasks`, strict dependency
verification e PostgreSQL local: **72 tasks executadas, 312 testes aprovados,
4 skips de symlinks por privilégio Windows, zero falhas/erros**. Nenhuma task
de teste foi reutilizada nessa execução final. Os quatro casos de symlinks
continuam obrigatórios e passaram na CI Linux.

Sequencialmente, playground `build test --rerun checkConnection` aprovado:
dois testes executados e PostgreSQL 17.11/porta 5432 confirmado. Não executar
run/bootstrap nem modificar os dados persistidos. Checks read-only confirmaram
4 clientes, 8 produtos, 4 encomendas, 8 itens e zero schemas de testes remanescentes.

Dois vetores PowerShell/.NET independentes coincidiram com o protocolo aprovado;
32 testes de gramática e 14 de planos/digests passaram sem skips. Os dois exemplos
do guia compilaram/executaram offline; cinco blocos Java do README e 34 fontes
geradas passaram 12 checks de compilação. A validação documental do #31 cobriu
174 links internos e preservou os 31 anchors do README. Auditorias dos commits,
blobs e relatórios locais não encontraram credenciais ou tokens.

| PR | HEAD revisto | Commit integrado | Gates |
|---|---|---|---|
| #28 | 908bb5b71978f4b1c43de9f0110442fa9ca48c26 | 894b408ddee6bfd98ad158c656f3909cf045d5b3 | 4 checks do PR + CI/CodeQL main aprovados |
| #30 | 2c5ee03af00a9f2a228ff836498b12f597874cf1 | fa9ebbfac408b18a5da046cd447cc9d1bfe6a6a8 | 4 checks do PR + CI/CodeQL main aprovados |
| #31 | 1c656e16fde3dd37bb87f4c90f0ab64f89ebcb69 | 1f36f2c55883711389b030dd87dd06fb95f36fab | 4 checks do PR + CI/CodeQL main aprovados |

CI main pós-#31: [CI](https://github.com/tuprel/tuprel/actions/runs/41205331330)
e [CodeQL](https://github.com/tuprel/tuprel/actions/runs/41205331305), análise
2068380802 com zero resultados. Documentação pública entregue nos próprios PRs:
guides, CLI/Java reference, help, índice, changelog e README aprovado. GIF original
preservado byte a byte; política de documentação integrada separadamente no #36.

Sem validações obrigatórias pendentes nestes três PRs; ADRs 0013–0017 aceites.
Limites mantidos: macOS e TLS remoto não exercitados; drift físico, executor,
locks de deployment e deploy/dev/diff não implementados. Não iniciar F6-003B
como continuação desta tarefa. O bootstrap do playground permanece temporário.

### Checklist para integrações seguintes

1. Consultar PR, HEAD, base, checks/reviews e ruleset reais. PR ready e sem conflitos.
2. CI obrigatória, CodeQL e Dependency Review aprovados **nesse HEAD**; nenhum
   blocker de review, skip injustificado, teste desactivado ou gate enfraquecido.
3. Testes locais obrigatórios aprovados; novos contratos críticos aprovados.
4. Revisão técnica/segurança do diff, auditoria de segredos/commits e confirmação
   de que não houve alteração de dados reais fora dos schemas de teste.
5. Respeitar integralmente a protecção da main. Native auto-merge observado como
   indisponível (`allow_auto_merge=false`); não alterar essa configuração. Usar
   merge explícito condicionado ao HEAD depois de verificar todos os critérios.
6. Verificar CI/CodeQL no novo HEAD da main. Falha suspende integrações; corrigir
   regressão antes de continuar. Actualizar branches por merge normal, sem force
   push/rebase destrutivo, preservando commits/autoria.

PRs empilhados podem usar a branch da entrega anterior como base para reutilizar
modelos sem duplicar alterações. CI, CodeQL e Dependency Review devem executar
também em bases feat/**, com os mesmos testes/gates. Retarget para main depois
de integrar a dependência, comparar o diff e repetir os checks do HEAD actual.
Não integrar uma dependência bloqueada só para desbloquear o PR seguinte.

Actualizar este plano após cada entrega com evidências reais. Uma pendência
administrativa não bloqueia trabalho offline independente; decisões novas de
execução continuam a exigir aprovação. Não publicar releases nem operar produção.
