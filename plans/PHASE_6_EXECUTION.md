# Fase 6 — Plano de execução contínua

Plano operacional único. A autorização do utilizador permite implementação
sequencial e integração dos PRs abrangidos **apenas** depois de cumprir todos
os gates abaixo. Não autoriza operações em produção nem decisões críticas
ainda pendentes. Java 21, PostgreSQL primeiro, CONTRIBUTING.md obrigatório.

## Estado observado

Continuação F6-004 iniciada a partir da main
`9fff107ff75afb2db95c16a44807ce98cd445191`, limpa e sincronizada.
CI/CodeQL reconsultados em 2026-10-31: runs 41236212986/41236212987 aprovados,
análise 2069309676 sem erro/resultados. Nenhum PR anterior é alterado.
ADR-0018 aprovado explicitamente em 2026-10-31 para comparação lógica offline.
F6-004A integrada no #44, commit 5e2bd1909dd695755e40e83ad522bfa289e20962.
CI/CodeQL da main aprovados (runs 41250178654/41250178624; análise 2069746352
sem erro/resultados). Não considerar F6-004 completa: projecção/CLI seguem.

F6-003B1–B4 e F6-003C integradas sequencialmente nos PRs #38–#42.
Último HEAD funcional desta entrega: `afd606859c026d88c0e6d2ad094e6d86f48fa396`.
CI/CodeQL desse commit aprovados antes de integrar este registo final
(runs 41234745072/41234745078; análise 2069266862 sem erro/resultados).
Base inicial `addc7275ec85fbb7c2634e3494b45688a0848401` preservada no histórico.
Runner Java e CLI deploy disponíveis no subconjunto aceite; diff/dev/drift
completo e adopção do playground continuam nas tarefas seguintes, sem antecipação.

Cada PR foi revisto e integrado apenas com os quatro checks do HEAD aprovados,
CodeQL sem resultados e testes locais obrigatórios aprovados. Main validada
entre entregas. Sem force push, alteração directa da main ou enfraquecimento
de quality gates. Auto-merge nativo indisponível; merges explícitos condicionados
ao SHA revisto e às protecções existentes.

### Histórico dos pré-requisitos

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

### Estado das entregas

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
| F6-003B1 definição/bootstrap | MERGED | ADRs 0014–0017; #28/#30/#31 integrados | feat/f6-003b-history-bootstrap / [#38](https://github.com/tuprel/tuprel/pull/38) | 3457c1ad519a6f4c7de2ec712145bad72c6c9fd7 | — |
| F6-003B2 sessão/locks/lifecycle | MERGED | B1 integrada e main validada | feat/f6-003b-session-locks / [#39](https://github.com/tuprel/tuprel/pull/39) | fe00a8364dcda077d5657080585ab1b33b3c2f4c | — |
| F6-003B3 uma migration atómica | MERGED | B2 integrada; main validada antes do PR | feat/f6-003b-atomic-migration / [#40](https://github.com/tuprel/tuprel/pull/40) | 6d8f979123525ab4b3aa1beb0696fd8d5600bafa | — |
| F6-003B4 sequência/falhas/reconciliação | MERGED | B3 integrada e main validada | feat/f6-003b-sequence-recovery / [#41](https://github.com/tuprel/tuprel/pull/41) | 2abf673b6f269f7984f91aca854e52be2120c231 | — |
| F6-003C CLI deploy | MERGED | B4 integrada e main validada | feat/f6-003c-migrate-deploy / [#42](https://github.com/tuprel/tuprel/pull/42) | afd606859c026d88c0e6d2ad094e6d86f48fa396 | — |
| F6-004A estados lógicos/diff Java | MERGED | ADRs 0015–0018 aceites | feat/f6-004a-schema-diff / [#44](https://github.com/tuprel/tuprel/pull/44) | 5e2bd1909dd695755e40e83ad522bfa289e20962 | — |
| F6-004B projecção de schema.tuprel | BLOCKED | 004A; ADR-0019 proposto | feat/f6-004b-schema-projection / [#45](https://github.com/tuprel/tuprel/pull/45) (draft) | — | Aprovação de mapping/dependência/names antes de implementar |
| F6-004C CLI diff | TODO | 004B; fontes/opções/exit codes definidos | — | — | CLI apenas com comportamento completo e launchers testados |
| F6-005A contrato/leitura de catálogo | TODO | 004 consolidada; ADR de catálogo | — | — | Snapshot read-only, completude, permissões e desconhecidos |
| F6-005B comparação de drift | TODO | 005A | — | — | Distinguir drift confirmado de observação desconhecida |
| F6-005C propostas/dev | TODO | Diff/drift; aprovação de novos contratos de escrita | — | — | Artefactos, aprovação contextual e revalidação; sem reset |
| F6-006 consolidação | TODO | Subconjuntos anteriores integrados | — | — | Matriz, segurança, documentação e desempenho medido |
| F6-006 playground baseline | TODO | Motor estável; tarefa isolada com plano de preservação | — | — | Inventário read-only e consentimento antes de adoptar migrations |
| F6-006 ignore de create.lock | TODO | Contrato de init/ownership | — | — | Evitar inclusão acidental sem sobrescrever .gitignore |

## Critérios e testes por entrega

### F6-004A — Comparação lógica offline

API no adapter existente: SchemaSnapshot reconstitui o estado lógico totalmente
conhecido por replay do AST aceite; SchemaDiff compara nomes exactos, tipos,
nullability, PK inline e indexes simples. Reutiliza parser/identificadores e
renderer; sem dependências/módulos novos, I/O, JDBC ou alterações aos digests.
Projecção de schema.tuprel e catálogo não são antecipadas.

Aceitação: determinismo independente de declaração de tabelas/colunas,
ordem de chaves do index significativa; sem inferência de rename; input SQL
desconhecido/draft recusa todo o estado. Referências incompletas, colisões,
targets/reservados e budgets recusados. Remoções/alterações bloqueiam todo o
preview. Adições conhecidas são propostas com riscos explícitos, nunca SAFE;
PHYSICAL_STATE_NOT_CHECKED existe mesmo em NO_CHANGES. Estado imutável e
comparações concorrentes sem resultado dependente da ordem de threads.

Validação focada: 14 testes unitários novos, JavaDoc com Werror e dois testes
novos PostgreSQL reais aprovados. Estes aplicam explicitamente o preview apenas
na fixture isolada para confirmar tipos/nullability/chaves e verificam que
comparar não altera catálogo/dados/histórico. Build completa da raiz aprovada:
391 testes nos relatórios, 387 aprovados, zero falhas/erros, quatro skips Windows
de symlinks já justificados e obrigatórios na CI Linux. Suites de integração
reexecutadas; tasks inalteradas reutilizadas quando up-to-date. Playground
build/checkConnection aprovado depois da raiz (PostgreSQL 17.11, sem Docker),
com dois testes inalterados reutilizados. Sem run/bootstrap.

Exemplo LogicalDiffExample compilado com Java 21, -Xlint:all/-Werror e executado
offline. Os três exemplos anteriores do guia compilam; os dois offline executam.
Links/anchors e identidade do README preservados; GIF com SHA-256 original
a157d3934c5b6b8999180a4831543644e1545130b7d239e1c2b6e53242347e5b.
Branch publicada sem force push; aprovação explícita do ADR-0018 recebida.
Gates remotos e HEAD actual podem ser consultados nos checks do PR;
integração só após testes/revisão e quatro checks aprovados nesse HEAD.
Revisão de arquitectura/segurança: sem novas dependências ou ciclos, sem SQL
raw de execução, mutabilidade partilhada, ligação JDBC ou escrita no diff.
Metadados/SQL só são expostos por acesso explícito; logging incidental e erros
novos omitem input. Auditoria pré-push: um commit/18 blobs/575 relatórios,
zero segredos encontrados. A primeira compilação
detectou JavaDoc sem resumo e dois avisos Error Prone nos testes; corrigidos
no código, mantendo Werror e todos os gates activos.

Permissões, snapshots concorrentes e perda de ligação do futuro leitor de
catálogo continuam critérios de F6-005A, que ainda não foi implementada.
Os testes existentes de status/runner permanecem obrigatórios na build completa.
Sem escrita do produto nova, sem CLI fictícia, sem adopção do playground.
ADR-0018 aceite por aprovação explícita, não por inferência de CI verde.
As fatias seguintes só integram com os contratos
necessários aprovados. Sem reset ou execução nova autorizada por este diff.

Integração #44: HEAD revisto 08115c85da65e483acbda411a208eba73bd4006d,
quatro checks aprovados e análise CodeQL 2069733838 sem erro/resultados.
Sem conflitos/reviews bloqueadoras; políticas reconsultadas, sem bypass.
Auto-merge nativo indisponível; merge normal condicionado ao SHA. Tree da main
igual ao HEAD revisto. CI/CodeQL pós-merge confirmados antes de iniciar 004B.

### F6-004B — Projecção completa ou recusada

ADR-0019 proposto define mapping dos oito scalars para os tipos já admitidos,
NOT NULL/PK, indexes simples com nomes legíveis limitados e recusas integrais
de defaults/relações/unique/enums/listas/tipos desconhecidos. Reutiliza o modelo
validado do compiler, AST e snapshots existentes, sem parser ou tipo paralelo.
API e contrato posterior de CLI diff são concretos; não anunciam comando novo.

A aresta proposta migrate-postgresql -> schema altera explicitamente a aresta
futura migrate -> schema do ADR-0013. Justificação: mapping físico pertence ao
dialecto, sem contaminar o núcleo ou criar um DTO intermédio redundante. Não
acrescentar a dependência nem implementar a API antes da decisão do utilizador.
O commit inicial desta branch é apenas documentação; nenhum teste de projector
ou CLI diff é apresentado como executado. Depois da aprovação, implementar
projector/testes neste mesmo PR; manter CLI em 004C separada.

Aceitação prevista: mapping completo ou snapshot ausente; diagnósticos seguros
por localização; nomes exactos/63 bytes e colisões; ordem de chaves do index;
sem defaults inventados; limites e concorrência; round-trip do preview e prova
PostgreSQL em schemas isolados. Contrato CLI posterior em ADR-0019, sem I/O no
help e com exit codes explícitos. F6-005 continua dependente de 004 consolidada
e contrato próprio de catálogo/UNKNOWN/DRIFT antes de novas operações de escrita.

### F6-003B1 — Definição canónica e bootstrap interno

Definição v1 única em MigrationHistoryTable, package-private no adapter:
colunas/tipos e constraints usados pelo validador existente e pelo DDL confiável
de bootstrap. Identificadores validados/quoted; tipos e operadores explicitamente
pg_catalog, sem IF NOT EXISTS, criação de schema ou adopção silenciosa.
O DDL interno não passa pela gramática de SQL de migrations do utilizador.

B1 produz a definição/statement interno e prova bootstrap real nas fixtures,
sem disponibilizar uma API de escrita antes de B2/B3 estabelecerem os gates de
sessão, lock, plano e aprovação. O status permanece exclusivamente read-only.
Esta divisão evita expor um bootstrap sem lock entre PRs; não altera o contrato
de deployment aprovado. Nenhuma interface vazia ou comando futuro é criado.

Testes novos: dois unitários (determinismo, metadata imutável e nomes hostis)
e quatro PostgreSQL (commit reconhecido pelo reader, rollback sem histórico,
colisão preserva dados existentes, search_path não redirecciona o alvo).
As fixtures antigas de histórico mantêm SQL independente como regressão do
formato já suportado. Build raiz: 318 aprovados, quatro skips Windows de
symlinks justificados; Linux continua a exigi-los. Playground build/checkConnection
passou depois da raiz, com dois testes inalterados reutilizados. Sem Docker,
sem alteração de dados do playground, sem execução de ficheiros de migrations.

A primeira CI da B1 detectou uma suposição errada na fixture do teste de
search_path: duas instâncias TestPostgresql usam bases distintas no modo
container. Corrigido para criar os dois schemas na mesma base da fixture,
com cleanup explícito do schema adicional. Build local completa repetida e
aprovada após a correcção; os quatro checks do HEAD df094c4 passaram e o #38
foi integrado sem contornar protecções. CI e CodeQL da main 3457c1a passaram
(runs 41213387160 e 41213387131; análise CodeQL sem resultados). Nenhum teste,
assert ou quality gate foi retirado.

B2 acrescentará aquisição/validação de sessão e lock com prazo; B3 ligará a
definição ao caminho de escrita controlado após reobservar/recompilar/verificar
aprovação, e aplicará uma migration. B4 validará sequência, falhas de commit/
rollback/cleanup e reconciliação real. Deploy só depois de todos esses gates.

### F6-003B2 — Sessão dedicada, lock e lifecycle

Componentes package-private no adapter; sem API pública de execução, DDL,
histórico novo ou comando CLI. A sessão valida URL configurado, database,
CURRENT_USER/SESSION_USER, acesso USAGE ao schema e TLS efectivo; recusa
schemas de sistema, transacção activa e qualquer advisory lock preexistente.
search_path é fixado em pg_catalog antes das consultas, mesmo que o DataSource
forneça outro contexto. Chave SHA-256/encoding idêntica ao ADR-0017; prazo monotónico de 30s, polling
interruptível de 100ms. Sem retries de ligações ou SQL após falha JDBC.

O chamador tem de fornecer connections idle com timeouts finitos e descarte
físico/abort, sem transaction-pooling. Falha de rollback produz OUTCOME_UNKNOWN;
falha de unlock/close exige descarte. Excepções não retêm causas cruas do driver.
Não há setAutoCommit(true) após falhas nem unlock-all. O status continua read-only.

Cobertura: vector SHA-256 independente; alvos/prazos inválidos sem ligar;
falha de ligação sem retry; lock sobrevive a commit/rollback; contenção com prazo;
locks independentes entre schemas da mesma base; recusa de sessão ocupada/alvo
divergente; interrupção antes de ligar e durante espera; falhas de rollback,
unlock e close com descarte e redacção. Todos os schemas pertencem às fixtures.
A primeira execução detectou que uma thread já interrompida podia chegar ao
driver e receber CONNECTION: corrigido para recusar antes de abrir a ligação.
Build raiz aprovada: 331 testes (incluindo seis do build-logic), quatro skips
Windows de symlinks já justificados e mantidos obrigatórios em Linux. Dez testes
novos de integração PostgreSQL e três unitários passaram. Build/checkConnection
do playground aprovada depois da raiz; dois testes inalterados reutilizados.
JavaDoc Werror, verificação estrita de dependências e links/anchors passaram.
Os quatro checks do HEAD 37c330a passaram; CodeQL sem resultados e revisão
sem bloqueadores/threads pendentes. #39 integrado normalmente em fe00a836;
CI e CodeQL da main fe00a836 aprovados (runs 41226425520 e 41226425527;
análise 2069020013, sem resultados/erro), antes de abrir o PR B3. Auto-merge nativo indisponível no
repositório; merge explícito condicionado aos gates e ao SHA revisto.

### F6-003B3 — Uma migration atómica, interna

MigrationRunner package-private aplica no máximo um script pendente. Redescobre
ficheiros e observa histórico REPEATABLE READ/READ ONLY na connection dedicada
sob lock; recompila o plano e verifica aprovação antes de bootstrap. NO_CHANGES,
BLOCKED, divergência e planos com mais de um script não iniciam DDL.

Bootstrap usa a definição canónica numa transacção própria. A migration usa
READ COMMITTED, ROW EXCLUSIVE no histórico conhecido, validação de estrutura/
índice canónico e locks ONLY determinísticos nos alvos existentes: ACCESS
EXCLUSIVE para ADD, SHARE para index. Identidade/OID validada após aquisição.
Tipos, collation/default opclass e estruturas desconhecidas são recusados.
Event triggers activos bloqueiam; não são desactivados. Nenhum schema é criado
pelo runner nem se adopta/repara um histórico desconhecido.

Só o renderer do AST aprovado fornece SQL de migrations. DDL e registo APPLIED
partilham commit; checksum original, tempos UTC com precisão PostgreSQL e duração
monotónica são registados. Falha de statement faz rollback; commit/conexão/
rollback incertos exigem descarte e OUTCOME_UNKNOWN, sem retry ou autocommit
como recuperação. Cleanup de uma transacção confirmada não repete rollback
nem apaga evidência de commit. O status e a CLI continuam inalterados.

Testes reais: SQL canónico/uma connection; DDL+histórico confirmados; erro de
statement ou INSERT faz rollback; artefacto/histórico alterado invalida digest;
unknown/NO_CHANGES sem bootstrap; histórico incompatível/índice extra recusados;
herança, RLS, regras, triggers, views/partições e tipos/collations não admitidos;
permissão CREATE ausente; perda real do ack simulado depois de commit confirmado
no servidor, sem retry; falha de cleanup preserva evidência confirmada.
Event trigger é observado por fault injection sobre
uma ligação real, sem criar objectos globais administrativos. Localmente a role
preexistente só detém um schema temporário; grants limitam-se a esse schema.
Na CI uma role LOGIN restrita, com password aleatória só em memória, existe
apenas na base/container descartável da fixture. Usa uma autenticação real;
não simula um login com SET SESSION AUTHORIZATION. O teste confirma que a
ligação corresponde ao contexto esperado e que CREATE está ausente.

Primeiros treze testes passaram e a build raiz inicial passou no checkout isolado, com
o caminho da fixture local explicitamente fornecido por init script ignorado,
sem ler/copiar credenciais. Depois da main B2 validada, os commits foram
preservados e a branch sincronizada por merge normal, sem reescrita. Build final
na raiz passou: 344 testes aprovados, quatro skips Windows justificados;
13 casos novos PostgreSQL passaram. Build/checkConnection do playground passou
em seguida (dois testes inalterados reutilizados), sem bootstrap. JavaDoc Werror,
dependências estritas, 121 links internos e 31 anchors passaram; os dois exemplos
Java do guia compilaram/executaram offline. Revisão técnica/segurança e checks
do HEAD continuam obrigatórios. A primeira CI Linux do #40 falhou na fixture
de permissões que simulava a sessão de outra role; corrigida para autenticação
real com role restrita exclusivamente no container. Nenhum gate do runner,
assert de permissão, teste ou configuração local foi enfraquecido/retirado.
Os quatro checks do HEAD 871a442 passaram depois da correcção; CodeQL sem
resultados, revisão sem bloqueadores/threads pendentes. #40 integrado por merge
normal em 6d8f979. CI e CodeQL da main aprovados (runs 41228720965 e
41228720945; análise 2069084460 sem erro/resultados), antes do PR B4.

### F6-003B4 — Sequências, falhas e reconciliação explícita

API embebida PostgresqlMigrationRunner no adapter: inspect read-only sob lock,
deploy com aprovação revalidada. Sem arestas novas, dependências novas, UI,
loader dotenv ou alteração dos bytes normativos de gramática/preview/digest.
MigrationExecutionResult separa outcome/failure do prefixo recém-confirmado,
imutável e ordenado. Uma transacção por migration; falhas terminam a sequência.
O reader de status e os comandos CLI continuam inalterados; deploy CLI fica para C.

Inspecção numa nova connection/sessão sob lock devolve histórico confirmado e
plano actual, sem DDL/bootstrap/repair/retry. Não reescreve o resultado anterior
nem promete drift completo/rollback global. Depois de OUTCOME_UNKNOWN, o
operador tem de reconciliar o histórico/artefactos e rever um novo plano antes
de outra invocação explícita. Bootstrap/prefixo confirmados mudam o digest;
rollback sem mudanças pode manter o mesmo hash, que não é token de uso único.
Não há persistência adicional de tentativas nem recuperação automática de crash.

Onze casos PostgreSQL adicionais: sequência ordenada; falha posterior preserva
prefixo; backend próprio terminado antes de commit; rollback falhado; bootstrap
com/sem commit confirmado no servidor e ack perdido; timeout de lock real;
dois executores sincronizados por barreiras; inspecção falha após rollback/
cleanup incerto; histórico adulterado entre commits; interrupção entre scripts;
cancelamento real com pg_cancel_backend, após observar espera em pg_locks.
O teste verifica lock_timeout=10s e statement_timeout=20s sem alterar limites
de produção. Só PIDs/objetos das fixtures são usados. O caso prévio de ack
perdido após commit também prova nova inspecção/APPLIED e recusa do digest antigo.

Quatro unitários validam invariantes/imutabilidade do resultado, ordem, erros
redigidos, ausência de retries e argumentos/alvos inválidos sem ligação.
Build inicial no checkout isolado aprovada: 359 testes, quatro skips Windows
justificados e mantidos obrigatórios em Linux. JavaDoc Werror preservado;
warnings iniciais de finally/serialização corrigidos. O PID passou a ser obtido
por JDBC/pg_backend_pid, evitando dependências transitivas de annotations do
driver. Build final repetida na raiz, sem init script: 359 aprovados e quatro
skips Windows justificados. Build/checkConnection do playground passou em
seguida; dois testes inalterados reutilizados, sem bootstrap/run. Dois exemplos
do guia compilaram/executaram offline; a classe do novo exemplo Java compilou
e os fluxos reais passaram nas fixtures PostgreSQL. 136 links internos e 31
anchors passaram; GIF aprovado preservado byte a byte. Revisão/CI no HEAD
Os quatro checks do HEAD 029622f passaram, CodeQL sem resultados e revisão
sem bloqueadores/threads pendentes. #41 integrado normalmente em 2abf673;
CI/CodeQL main aprovados (runs 41231854991/41231855189, análise 2069183368
sem erro/resultados) antes do PR C. Nenhuma protecção foi contornada.

### F6-003C — CLI deploy

Comando implementado sobre o runner, sem lógica de SQL/histórico/execução própria.
Exige --target-schema e exactamente um de --dry-run ou --approve-digest.
Configuração de produção explícita, sem .env/test loader; help/erros de opções
não acedem a ambiente/files/DB. Só ficheiros v1 existentes; schema.tuprel não
é lido para gerar DDL. CLI sem prompts/ANSI, exit 0 para sucesso/preview válido,
2 para recusa/falha/incerteza; comandos existentes mantêm os códigos.

Dry-run reutiliza inspect read-only numa nova sessão sob advisory lock, em vez
da observação sem lock inicialmente proposta no roadmap CLI. Não usa os locks
DDL do writer nem escreve schema/histórico/ficheiros. Esta orquestração usa
o contrato B4 aceite, mantém revalidação independente antes da aplicação e
permite a mesma inspecção para reconciliação explícita após OUTCOME_UNKNOWN.
O help documenta espera/LOCK_BUSY; status mantém a observação sem advisory lock.

Sete unitários: help sem acesso; opções ambíguas/duplicadas/digest inválido;
dotenv ignorado; preview canónico/riscos/digest sem comentários crus; plano
bloqueado sem SQL parcial/aprovação; prefixo e UNKNOWN claros; diagnósticos
seguros/códigos estáveis. Cinco PostgreSQL end-to-end pelo launcher real:
PowerShell/BAT local e Bash em Linux; preview/aprovação/no-op/replay recusado,
ficheiro alterado/unknown, configuração ausente, falha parcial e perda real
da conexão durante espera de lock, seguida de nova inspecção/revisão explícita.
Só schemas e PIDs próprios das fixtures. Nenhuma role local foi criada/alterada.

Uma primeira execução falhou na fixture PowerShell porque quotes de -split
eram removidas ao passar -Command pelo processo. Corrigida para [char]10,
sem mudar CLI nem retirar asserts. Cinco casos repetidos/aprovados. Build
inicial completa passou: 371 aprovados, quatro skips Windows justificados;
base B4 sincronizada por fast-forward. A validação final detectou um warning
StringConcatToTextBlock num novo teste de preview; corrigido sem supressões.
Build final na raiz passou: 371 aprovados, quatro skips Windows justificados;
playground build/checkConnection passou em seguida, com dois testes inalterados
reutilizados e sem bootstrap/run. Três exemplos Java compilaram (dois executados
offline; runner exercitado nos testes PG); 197 links internos e 31 anchors
passaram. Os quatro checks do HEAD 7186b30 passaram; análise CodeQL 2069248221
sem erro/resultados e revisão sem bloqueadores/threads pendentes. #42 integrado
normalmente em afd6068. CI/CodeQL desse HEAD funcional aprovados (runs
41234745072/41234745078, análise 2069266862 sem erro/resultados).

Verificação read-only local após os testes confirmou as contagens anteriores
dos dados persistidos do playground e zero schemas de teste restantes, sem
expor valores ou credenciais. Bootstrap manual preservado. O GIF aprovado
permanece byte a byte igual. Nenhum teste novo foi ignorado; os quatro skips
Windows existentes passaram obrigatoriamente na CI Linux. macOS não executado,
por ausência de runner nessa matriz. Sem operações em produção nem publicação.

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
