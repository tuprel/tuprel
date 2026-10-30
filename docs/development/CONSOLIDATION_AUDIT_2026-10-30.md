# Auditoria e consolidação — 30 de Outubro de 2026

## Estado verificado do repositório

Inspecção local e GitHub, sem push, merge ou publicação:

- `origin/main`: `cd406e609cc46e46ea4037e001bda5f4f91ab601`, merge do PR #10.
- [PR #11](https://github.com/tuprel/tuprel/pull/11): **aberto**, não integrado,
  head `ce07dfab3f6c2d461ca11e44d91cd32e03a72a4e`, oito commits,
  59 ficheiros alterados. Não havia reviews submetidas na consulta.
- O commit do PR #11 tinha CI, Dependency Review e CodeQL concluídos com sucesso:
  [CI](https://github.com/tuprel/tuprel/actions/runs/36787133744),
  [Dependency Review](https://github.com/tuprel/tuprel/actions/runs/36787133983),
  [CodeQL](https://github.com/tuprel/tuprel/actions/runs/36787133869).
  Estes resultados antecedem as correcções desta entrega.
- Working tree inicialmente limpa em `main`. Trabalho preservado numa branch
  local `codex/local-postgresql-playground`, baseada no head do PR #11.

| Capacidade | Em `main` | Na base do PR #11 |
|---|---|---|
| Parser, AST, formatter, validação, models/enums/scalars | Implementado | Mantido, validação de relações mais estrita |
| CLI `validate`, `format`, `generate` | Implementado | Mantido |
| Codegen de records, inputs e metadata | Implementado | Inclui estado de relações e metadata partilhada |
| CRUD PostgreSQL e binding tipado | Implementado | Mantido |
| Cliente operacional, filtros, NULL, projecções, count/exists | Implementado | Mantido |
| Ordenação, paginação offset/cursor e SQL preview | Implementado | Mantido |
| 1:1, 1:N, auto-relações, N:N com model de junção | Ausente | Implementado |
| Include explícito/aninhado e carregamento por lotes de chaves | Ausente | Implementado |
| Transacções, savepoints, isolamento, read-only | Ausente | Implementado, com failure paths corrigidos nesta entrega |
| Batch, bulk writes, optimistic locking, locks, timeout, streaming | Ausente | Implementado para o subconjunto RFC-005 |
| Migration engine, introspecção e plugins de produto Gradle/Maven | Ausente | Ausente |
| N:N implícita, filtros de relações, escritas aninhadas, upsert | Ausente | Adiado explicitamente |

A documentação de `main` apontava correctamente relações/transacções como
futuras. A documentação na branch do PR descreve a implementação da Fase 5,
não uma funcionalidade já integrada em `main`. O código real reside nos seis
módulos existentes; não existe `tuprel-migrate` nem comando CLI de migrations.
O gate 1.0 permanece por cumprir, incluindo matriz de compatibilidade,
migrations, API estabilizada e release engineering.

## Avaliação técnica do PR #11

Revisão de `RelationGraph`, geração/contratos compiláveis, `RelationLoader`,
`ModelOperations`, AST/renderer SQL, transacções, scopes, streams e testes.

Constatações positivas confirmadas no código:

- Relações têm estado carregado explícito; os getters não executam SQL.
- Includes usam chaves distintas em lotes de 500 e agrupamento por chave,
  incluindo normalização numérica de decimais. O custo é
  `1 + soma(ceil(chaves/500))` por relação/nível, e não uma query por registo.
  O texto resumido do PR simplifica este custo; o RFC-003 descreve-o exactamente.
- N:N requer uma tabela/model de junção explícito. Não existe criação implícita
  de tabelas, escrita de grafos ou cascata ORM.
- SQL usa identificadores validados/citados e valores vinculados, incluindo
  filtros, LIMIT/OFFSET, versões e batches. Não encontrei concatenação de
  valores externos nestes percursos.
- Operações normais fecham JDBC por try-with-resources. Streaming é explícito,
  exige transacção e regista recursos no scope para fecho no final.
- Batch de até 30 000 binds por statement usa INSERT multi-linha; vários
  statements exigem transacção. Optimistic locking actualiza versão e dados
  num único statement condicionado.
- Existem testes PostgreSQL para includes com 1 200 pais, savepoints, FK,
  isolamento, locks concorrentes, timeout, oito escritores optimistas,
  streaming e atomicidade por violação de unicidade. A execução local destes
  cenários tem de ser confirmada; existência do teste não equivale a execução.

Problemas corrigidos nesta branch:

| Prioridade | Problema observado | Correcção/regressão |
|---|---|---|
| P1 | Depois de rollback falhado, `setAutoCommit(true)` podia confirmar alterações pendentes | Não restaurar autocommit nesse caso; tentar abortar a connection antes do close e preservar a falha original/suppressed |
| P1 | Falha de rollback/libertação de savepoint podia ser tratada pela aplicação e seguida de commit exterior | Marcar o scope e os seus pais apenas para rollback; recusar commit |
| P2 | Savepoints revertidos permaneciam activos | Libertar após rollback bem sucedido |
| P2 | Falha na configuração inicial fechava sem tentar repor definições já alteradas | Recuperar/restaurar quando o estado da connection permite fazê-lo com segurança |
| P2 | Transacção por omissão podia herdar `readOnly=true`, embora as opções pedissem escrita | Aplicar sempre o modo pedido e repor o modo original |
| P2 | `TuprelStream.close()` podia operar JDBC noutra thread | Validar o scope antes de fechar um stream activo |
| P2 | A API estrutural de baixo nível aceitava row lock em autocommit | Rejeitar antes de abrir connection, tal como a API gerada |
| P2 | Timeout de 1 segundo + 1 nanossegundo arredondava para 1 segundo; durações enormes podiam transbordar | Arredondar exactamente por segundos/nanos, saturar o timeout JDBC e validar limite de prazo transaccional antes de abrir recursos |

Sem alterações de assinaturas públicas. As mudanças incompatíveis já presentes
no PR (construtor canónico com relações, `P.metadata` e update versionado) estão
documentadas no ADR-0011/RFCs. Não há artefactos públicos publicados a migrar.

A tentativa de `abort` evita devolver deliberadamente uma transacção sem estado
conhecido a um pool. Segundo o contrato [JDBC Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html),
o resultado de `close` com transacção activa depende da implementação. Se um
driver/proxy também falhar no abort, essa falha fica suprimida e o close continua
a ser tentado; o runtime não pode garantir recuperação de uma ligação avariada.

Limites que continuam deliberados: includes sem transacção não dão um snapshot
único; para consistência entre statements usar transacção e isolamento
apropriado. O segundo SELECT após update optimista sem linhas distingue
ausência/conflito no momento dessa leitura. Não há retries automáticos nem
verificação de versão no delete. Um timeout JDBC tem granularidade de segundos
e não é um mecanismo de interrupção arbitrária do callback Java.

## Dependências e segurança

O [PR #15](https://github.com/tuprel/tuprel/pull/15) continua aberto. Só altera
as declarações 42.7.13 para 42.7.14; não actualiza lockfile nem verification
metadata. Esta entrega inclui as três partes, sem merge do PR.

42.7.14 corrige CVE-2026-107314 e CVE-2026-107315 segundo as
[notas oficiais pgJDBC](https://jdbc.postgresql.org/changelogs/2026-10-07-42.7.14-release/).
A primeira afecta certas configurações de `requireAuth`; a segunda envolve
preenchimento de buffers em APIs específicas do driver. Não afirmo que todos
esses percursos são atingíveis pelo binding escalar do Tuprel; actualizar o
driver remove a dependência afectada.

JAR/POM obtidos por HTTPS no Maven Central, SHA-1 comparado com os sidecars
publicados e SHA-256 calculado para dependency verification:

- JAR: `73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c`
- POM: `b7e66ccf3088cba04d68ec374da4e981686fafe426e4ca69d6d276427175dc63`

Isto verifica consistência com a origem; não equivale a validação independente
de assinatura. Dependency verification continua em modo strict.

Outros PRs pendentes encontrados: #12 setup-gradle 6.4.0, #17 Spotless 8.10.4,
#19 setup-java 6.1.0, #21 Wrapper 9.8.2 e #22/#23 CodeQL 4.39.1. Foram
inventariados, sem misturar estas actualizações na consolidação. Não foi
consultada uma API de alertas de segurança do repositório; não há afirmação de
ausência de outros alertas.

## Configuração local e demonstração

- Três suites existentes conservadas e adaptadas à fixture `TestPostgresql`.
  Modo local por omissão; Testcontainers só por flag explícita na CI.
- URL loopback, porta explícita, base exacta `tuprel_devtest`, sem parâmetros
  nem credenciais no URL. Confirmação de catálogo antes de DDL.
- Um schema aleatório por suite; search path exclusivo, incluindo threads
  concorrentes. Limpeza apenas desse schema. Não se apagam bases de dados.
- Integração não reutiliza resultado up-to-date/build-cache, pois a base é externa.
- Exemplo Gradle independente com composite build, Wrapper, locks e catálogo
  de verificação próprio sincronizado com a raiz. Sem publicação Maven.
- Schema e cliente reais para quatro models comerciais. `run` verifica resultados
  e termina com erro se um comportamento esperado não se confirmar.
- Bootstrap SQL manual e transaccional, limitado ao schema persistente
  `tuprel_playground`. Não é migration, não detecta drift e não evolui DDL existente.
- Instruções PowerShell e IntelliJ no [README do exemplo](../../examples/tuprel-playground/README.md).

## Ambiente e validação

Na inspecção inicial: Java 25, sem JDK 21 detectado, sem serviço PostgreSQL,
sem instalação em Program Files/registo e sem as três variáveis configuradas.
O utilizador informou que o PostgreSQL ainda estava a ser instalado.

Foi obtido Temurin JDK 21.0.13+9 através da API oficial Adoptium, verificado
com SHA-256 e extraído em `.jdks` do utilizador. Sem alteração de PATH/JAVA_HOME
global: os comandos abaixo seleccionam o JDK apenas no processo PowerShell.

Comandos executados na raiz (todos com `--dependency-verification strict`):

| Comando PowerShell | Resultado observado |
|---|---|
| `.\gradlew.bat test :tuprel-postgresql:compileIntegrationTestJava spotlessCheck --no-daemon` | Sucesso. 119 testes unitários aprovados, zero falhas; um teste de symlinks ignorado. As três suites PostgreSQL compilam |
| `.\gradlew.bat :tuprel-postgresql:dependencies --update-locks org.postgresql:postgresql --no-daemon` | Sucesso. Driver/lock actualizado para 42.7.14 |
| `.\gradlew.bat check --continue --no-daemon` | **Falhou** na inicialização das três suites PostgreSQL: `TUPREL_TEST_JDBC_URL` ausente. Nenhum teste funcional de base de dados executou; nenhum container iniciou. Os seis testes TestKit do build-logic passaram |
| `.\gradlew.bat -p examples/tuprel-playground build --write-locks --no-daemon --warning-mode all` | Sucesso. Schema validado, cliente gerado, aplicação/distribuição compilada em Java 21 e dois testes de segurança do target aprovados, sem warnings de depreciação novos |
| `.\gradlew.bat spotlessCheck --no-daemon` | Sucesso após alargar o gate aos fontes/build do exemplo |
| `.\gradlew.bat :tuprel-runtime:test spotlessCheck --no-daemon` | Sucesso após reforçar abort/close. 19 testes de transacções aprovados; totais dos módulos: 120 aprovados, um ignorado e zero falhas |
| `.\examples\tuprel-playground\gradlew.bat -p examples/tuprel-playground run --no-daemon` | **Falhou com exit code 1**, por ausência da configuração de ambiente. Wrapper próprio funcional; nenhuma gravação na base foi validada |
| `git diff --check` | Sucesso |

O único teste unitário ignorado foi
`GeneratedSourceWriterTest.rejectsSymlinkInsideOutputTree`: a criação de
symlinks não está disponível neste ambiente Windows. Não foi removido nem
enfraquecido. O teste continua activo para ambientes que permitam symlinks.

Somando os módulos, os seis testes de build-logic e os dois do exemplo,
há 128 testes aprovados e um ignorado nos relatórios locais. As três falhas
de inicialização PostgreSQL são reportadas separadamente; não contam como
cobertura funcional executada.

Tentativas iniciais falharam por falta de JDK 21, por um finding de Error Prone
sobre retorno ignorado e por catálogo de verificação incompleto no composite
build do exemplo. Foram corrigidos sem desactivar os quality gates. Uma
compilação também encontrou erro interno de Error Prone enquanto os fontes
estavam a ser alterados; repetida com fontes estáveis, passou. As depreciações
do DSL Gradle do exemplo foram corrigidas e a build repetida sem esses avisos.

**Gate ainda aberto:** não há confirmação local de CRUD/relações/transacções,
locks, timeouts ou gravação persistente pelo exemplo enquanto não estiverem
disponíveis URL, utilizador e password de testes. O `check` completo não está
verde e a primeira entrega não deve ser aceite como validação funcional
concluída. No final da preparação já foi detectado o serviço
`postgresql-x64-17` em execução; faltava a configuração de acesso.
O listener do processo PostgreSQL foi confirmado na porta 5432, sem presumir
essa porta a partir de defaults. Não foram lidos ficheiros de credenciais.

## Ficheiros e responsabilidades

- `tuprel-runtime`: lifecycle transaccional, scope, streaming, limites de timeout
  e testes de regressão em `TransactionTest`.
- `tuprel-postgresql`: versão/lock do driver, fixture local, validação de target
  e adaptação das três suites existentes sem eliminar casos.
- `gradle/verification-metadata.xml`: checksums do driver actualizado.
- `.github/workflows/ci.yml`: modo container explícito e compilação do consumidor.
- `examples/tuprel-playground`: build independente, Wrapper, locks/checksums,
  schema, bootstrap SQL, aplicação e documentação de execução.
- `README.md`, `docs/development/BUILD_AND_TEST.md`,
  `docs/testing/TEST_STRATEGY.md` e RFC-005: comportamento actualizado.
- `plans/LOCAL_POSTGRESQL_CONSOLIDATION.md`: âmbito e critérios desta entrega.

## Próximos passos e gate de migrations

1. Completar a execução em PostgreSQL local com configuração do utilizador,
   incluindo `check`, os testes concorrentes e duas execuções do playground.
2. Rever os commits locais de runtime, infra-estrutura e exemplo.
   Push/merge do PR #11 ou de qualquer outra branch requer autorização separada.
3. Antes de implementar migrations: aprovar RFC/ADR sobre formato imutável dos
   ficheiros, history/checksum, identificação do target, lock concorrente,
   transacções e falhas parciais, drift e operações destrutivas.
4. Dividir a Fase 6 em fatias: modelo/checksum puro; history/status read-only;
   aplicação ordenada de SQL revisto com lock e rollback; diff/preview e safety;
   drift; CLI dev/deploy. Cada fatia com critérios e testes reais de concorrência,
   checksum alterado e falhas. Reset e detecção de renames ficam sujeitos a
   decisões explícitas. `deploy` nunca deve inventar DDL a partir do schema.

Nenhum motor de migrations foi iniciado nesta entrega.

## Commits locais preparados

- `baa811b` — `fix(runtime): protect transactions after recovery failures`
- `1b84797` — `test(postgresql): support isolated local database integration`
- `887979d` — `feat(examples): add local PostgreSQL commerce playground`
- `b51154e` — `fix(runtime): abort connections when rollback cannot recover them`

Sem push ou merge. O relatório e plano desta entrega ficam num commit de
documentação adicional.

## Lista exacta dos ficheiros alterados face ao head do PR #11

- `.github/workflows/ci.yml`
- `FILE_INVENTORY.md`
- `README.md`
- `build.gradle.kts`
- `docs/development/BUILD_AND_TEST.md`
- `docs/development/CONSOLIDATION_AUDIT_2026-10-30.md`
- `docs/rfcs/RFC-005-transactions-concurrency.md`
- `docs/testing/TEST_STRATEGY.md`
- `examples/tuprel-playground/README.md`
- `examples/tuprel-playground/build.gradle.kts`
- `examples/tuprel-playground/gradle.lockfile`
- `examples/tuprel-playground/gradle/verification-metadata.xml`
- `examples/tuprel-playground/gradle/wrapper/gradle-wrapper.jar`
- `examples/tuprel-playground/gradle/wrapper/gradle-wrapper.properties`
- `examples/tuprel-playground/gradlew`
- `examples/tuprel-playground/gradlew.bat`
- `examples/tuprel-playground/settings-gradle.lockfile`
- `examples/tuprel-playground/settings.gradle.kts`
- `examples/tuprel-playground/src/main/java/dev/tuprel/playground/LocalDatabase.java`
- `examples/tuprel-playground/src/main/java/dev/tuprel/playground/Playground.java`
- `examples/tuprel-playground/src/main/resources/bootstrap.sql`
- `examples/tuprel-playground/src/test/java/dev/tuprel/playground/LocalDatabaseTest.java`
- `examples/tuprel-playground/tuprel/schema.tuprel`
- `gradle/verification-metadata.xml`
- `plans/LOCAL_POSTGRESQL_CONSOLIDATION.md`
- `tuprel-postgresql/build.gradle.kts`
- `tuprel-postgresql/gradle.lockfile`
- `tuprel-postgresql/src/integrationTest/java/dev/tuprel/postgresql/GeneratedQueryIntegrationTest.java`
- `tuprel-postgresql/src/integrationTest/java/dev/tuprel/postgresql/PostgresqlDatabaseIntegrationTest.java`
- `tuprel-postgresql/src/integrationTest/java/dev/tuprel/postgresql/RelationsAndTransactionsIntegrationTest.java`
- `tuprel-postgresql/src/integrationTest/java/dev/tuprel/postgresql/TestPostgresql.java`
- `tuprel-postgresql/src/test/java/dev/tuprel/postgresql/LocalPostgresqlTargetTest.java`
- `tuprel-postgresql/src/testSupport/java/dev/tuprel/postgresql/LocalPostgresqlTarget.java`
- `tuprel-runtime/src/main/java/dev/tuprel/runtime/TransactionOptions.java`
- `tuprel-runtime/src/main/java/dev/tuprel/runtime/TransactionScope.java`
- `tuprel-runtime/src/main/java/dev/tuprel/runtime/TuprelDatabase.java`
- `tuprel-runtime/src/main/java/dev/tuprel/runtime/TuprelStream.java`
- `tuprel-runtime/src/test/java/dev/tuprel/runtime/TransactionTest.java`
