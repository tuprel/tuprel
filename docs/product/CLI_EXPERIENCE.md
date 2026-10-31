# TUPREL CLI — Produto, arquitectura e contrato v1

Estado: fundação/create/status integrados; runner Java F6-003B e CLI deploy
F6-003C seguem os ADRs 0012–0017 aceites. Dev/diff/drift completo ficam futuros.
Entrada pública: [CLI reference](../reference/cli.md). Este documento mantém o
desenho de produto e o roadmap interno, separados dos comandos disponíveis.
A tabela distingue código entregue de roadmap. Java 21. Sem publicação, instalador ou integração automática
com frameworks nesta entrega. A CLI é uma superfície principal do toolkit.

## Experiência implementada

```text
tuprel --help
tuprel --version
tuprel init [--dry-run]
tuprel validate [--schema <ficheiro> | schema.tuprel]
tuprel format [--check | --dry-run] [--schema <ficheiro> | schema.tuprel]
tuprel generate [--check | --dry-run] [--schema <ficheiro> | schema.tuprel]
tuprel migrate create --name <nome> [--dry-run]
tuprel migrate status --target-schema <identificador>
tuprel migrate deploy --target-schema <identificador> --dry-run
tuprel migrate deploy --target-schema <identificador> --approve-digest <sha256>
```

Cada comando implementado aceita `--help` ou `-h`, sem ler ficheiros. Help
global e versão requerem a opção isolada; mistura de opções não é ignorada.
Sem comando: usage em stderr e exit 2, preservando o contrato anterior.
Default: `tuprel/schema.tuprel` relativo à working directory, sem procura em
pastas superiores. `--schema` e path posicional são exclusivos. Duplicados,
opções desconhecidas e valores em falta falham com exit 2, sem efeitos.
`--` termina opções e permite um path posicional que comece por hífen.

Init funciona numa pasta de projecto existente (Gradle, Maven ou Java simples).
Cria apenas schema mínimo com generator/package editável e `User`, mais um
catálogo vazio versionado de migrations. Não cria base, não liga JDBC, não
instala bibliotecas, não escreve `.env`/pom/build.gradle e não infere framework.
Recusa qualquer `tuprel` existente, incluindo case alternativo, ficheiro ou
symlink. Não existe `--force`. Falha parcial deixa os ficheiros criados para
inspecção manual e devolve 2; não apaga ficheiros numa tentativa de rollback.

O default de package é `dev.example.tuprel`, visível no schema e para alterar
antes de gerar código para uma aplicação real. O developer tem de integrar as
bibliotecas e `build/generated/sources/tuprel/main` no seu build; os plugins
automáticos pertencem a fases posteriores. O init não promete configurar isso.

Num projecto Maven, o output mantém o default compatível `build/generated/...`
da CLI actual e pode ser integrado manualmente como source root. A convenção
`target/generated-sources/tuprel` do ADR-0007 pertence à futura integração Maven;
não se muda silenciosamente o destino ao detectar um pom.xml.

## Criação offline de migrations (F6-002A)

Requer catálogo v1 existente em `tuprel/migrations`; num projecto novo, executar
`tuprel init` primeiro. Não lê schema, `.env` ou base de dados. Não aceita
`--schema`, ID manual nem flags de aplicação. A CLI anuncia apenas os comandos
implementados: `migrate create` e `migrate status`.

O nome segue o ADR-0012: 1–64 caracteres ASCII minúsculos, começa por letra,
palavras separadas por `_` ou `-`. Exemplo: `add_products-v2`. Não converte
maiúsculas, espaços ou acentos silenciosamente. IDs UTC têm precisão de segundo;
colisão, relógio regressivo e limites excedidos são erros (exit 2), sem retries.

Cria `<uuuuMMdd_HHmmss>_<nome>/migration.sql` com comentário UTF-8/LF, pronto
para edição manual. Nenhum statement é inventado, executado ou aprovado.
`--dry-run` valida e mostra a pasta prevista sem escrever nem reservar o ID.

Criadores cooperam com lock não bloqueante num ficheiro vazio persistente
`tuprel/.migrations.create.lock`, fora do catálogo v1. Não apagar/substituir
com criadores activos; a sua presença não significa que há um lock activo.
O proprietário pode adicionar `/tuprel/.migrations.create.lock` ao `.gitignore`;
a CLI não modifica configurações. Precisa de escrita no catálogo e no pai.

Falha após criar a pasta preserva ficheiros e devolve PARTIAL_CREATION: inspeccionar
a pasta nova antes de repetir. Ficheiros incompletos bloqueiam descoberta, mas
uma falha após escrita completa pode deixar um draft válido sem confirmação de
sucesso. Não existe journal, rollback de filesystem ou aprovação implícita.
Leitores/editores externos devem excluir escritas concorrentes. Filesystems
remotos e adversários que trocam paths durante I/O ficam fora deste contrato.
Ver [protocolo e trade-offs](../../plans/F6-002A_MIGRATION_CREATE.md).

## Histórico e status (F6-002B)

`migrate status --target-schema <identificador>` consulta o catálogo local v1 e
o histórico da base configurada. `--schema` continua reservado ao ficheiro de
modelos e não substitui `--target-schema`. Não existe target implícito, procura
em pastas superiores, `--dry-run` ou criação de ficheiros neste comando.

Configuração de produção: `TUPREL_DATABASE_URL`, `TUPREL_DATABASE_USER` e
`TUPREL_DATABASE_PASSWORD` no ambiente do processo (por exemplo através da IDE
ou gestor de secrets). **A CLI nunca carrega .env**, nem o do playground.
Não passar passwords nos argumentos nem URLs. O URL deve ter host/porta/base
explícitos; a única opção aceite é sslmode=verify-full, ou disable em loopback.
Por omissão TLS verify-full fora de localhost/127.0.0.1/::1, disable em loopback.
Certificados usam o trust/root certificate do pgJDBC; não existem flags de bypass.
Timeouts connect/login 5s, queries 10s e socket 30s. Nomes de utilizador, URLs,
passwords e mensagens originais do driver não aparecem na saída normal.

Requer schema existente, USAGE e SELECT no histórico, mais leitura normal de
pg_catalog. A transacção é REPEATABLE READ, READ ONLY confirmada no servidor;
o snapshot só é devolvido após rollback e fecho bem-sucedidos. Nunca cria tabela,
schema ou linhas. Histórico ausente é UNINITIALISED; ficheiros locais são PENDING.

Saída sem cores, com colunas ID UTC, nome e estados independentes: APPLIED,
PENDING, MODIFIED, REMOVED, RENAMED e OUT_OF_ORDER. Uma linha pode acumular
divergências; o nome nunca substitui a identidade. Gaps/ficheiros retroactivos e
tempos de execução incompatíveis com a ordem dos IDs são assinalados.
Finaliza sempre com `DRIFT_NOT_CHECKED`: integridade de ficheiros/histórico não
prova equivalência do schema físico. Não interpreta nem executa migration.sql.

Exit 0 para normal/pending/uninitialised; 1 para divergências; 2 para uso,
configuração, permissões, estrutura desconhecida ou falha JDBC/limpeza. Não há
retries, repair, bootstrap ou aprovação para deploy. Estruturas aparentemente
equivalentes mas fora da allowlist conservadora falham: ver
[contrato de histórico](../../plans/F6-002B_MIGRATION_STATUS.md).

Leitores devem excluir alterações concorrentes ao catálogo local. O snapshot
PostgreSQL não é uma transacção conjunta com o filesystem; o comando não adquire
locks de deployment e não coordena DDL administrativo concorrente no histórico.

PowerShell, ambiente de produção já configurado de forma segura:

```powershell
.\gradlew.bat :tuprel-cli:installDist --dependency-verification strict
$tuprelCli = (Resolve-Path '.\tuprel-cli\build\install\tuprel\bin\tuprel.bat').Path
& $tuprelCli migrate status --help
$tuprelTargetSchema = Read-Host 'Schema alvo existente'
# Executar a partir do projecto com tuprel/migrations:
& $tuprelCli migrate status --target-schema $tuprelTargetSchema
$LASTEXITCODE
```

Os testes internos continuam a usar apenas TUPREL_TEST_* e o .env ignorado,
mapeados explicitamente pelo harness para processos CLI isolados. Não é uma API
de produção. Ver [testes e role read-only](../development/BUILD_AND_TEST.md).

## Deploy — inspecção e aprovação explícitas

CLI orquestra PostgresqlMigrationRunner; não contém parser SQL, lógica de
aprovação, DDL, histórico ou retries próprios. Catálogo fixo tuprel/migrations,
schema alvo existente e configuração TUPREL_DATABASE_* explícitos. Nunca
carrega .env/test config nem gera SQL a partir do schema.tuprel.

Dry-run usa inspect read-only numa nova sessão sob o advisory lock aceite.
Apresenta estado, histórico, preview canónico completo, riscos e digest;
unknown/destructive bloqueia o plano, sem expor SQL parcial ou aprová-lo.
Não cria ficheiros, schema, histórico ou registos. Não reserva o plano.
Após revisão, --approve-digest exige o SHA-256 exacto; flags são exclusivas,
sem --yes ou prompts. Deploy reobserva/recompila sob lock antes de escrever.

Refinamento da proposta offline: dry-run reutiliza a inspecção coordenada já
testada no runner, em vez de criar uma segunda observação sem lock. Adquire
somente o lock cooperativo de sessão, sem os modos de lock DDL do writer;
pode aguardar/recusar LOCK_BUSY. Isto fornece a mesma observação numa sessão
nova usada na reconciliação de OUTCOME_UNKNOWN. Não muda gramática, digest,
histórico ou gates de execução. Status continua sem advisory lock de deployment.

Cada migration tem commit DDL/APPLIED atómico; o resultado separa outcome do
prefixo recém-confirmado. Erro posterior não faz rollback global. UNKNOWN
implica parar, descartar e reconciliar histórico/artefactos numa nova inspecção;
sem retry, repair, token de uso único ou log persistente de tentativas. Driver/SQL
não aparecem em diagnósticos normais; SQL canónico só no dry-run explícito.
0 para sucesso/preview válido, 2 para recusa/falha/incerteza. Status mantém 1
para problemas de integridade. Help funciona sem env/files/DB.

## Contratos do terminal

| Aspecto | Contrato CLI |
|---|---|
| stdout | ajuda, versão, sucesso e resumo do dry-run |
| stderr | códigos de erro/diagnostics e instrução de correcção |
| Avisos | prefixo `WARNING:` em stderr quando existirem; riscos do plano integram o preview explícito em stdout |
| 0 | operação/preview concluído com sucesso |
| 1 | schema inválido, geração não suportada ou diferenças em `--check` |
| 2 | uso inválido, I/O, path inseguro, recusa de ownership/aprovação ou falha/incerteza DB |
| Cores | texto simples, sem ANSI, funciona com NO_COLOR e redirecção |
| Interacção | nenhum prompt nem leitura de stdin; comportamento igual em CI |
| Versão | `tuprel development` enquanto não existir versão de release; versão real do manifest quando atribuída |
| Encoding | entrada schema UTF-8; stdout/stderr UTF-8; paths com espaços são um único argumento quoted |

Nos comandos de ficheiros, `--dry-run` calcula/valida sem escrever; 0 quando o preview é válido, mesmo que
existam mudanças. Não mostra conteúdo do schema ou código que possa conter
valores sensíveis. Generate verifica ownership, paths e edições com a mesma
regra da escrita; edited/unowned continua bloqueado. `--check` compara estado
actual e devolve 1 se faltar output ou houver diferenças. Não combinar as duas.
Format escreve só o ficheiro seleccionado, recusa symlink no ficheiro e usa
NOFOLLOW_LINKS; a pasta explicitamente seleccionada é resolvida canonicamente.

Erros não mostram argumentos desconhecidos, paths arbitrários, valores do schema,
env, SQL ou mensagens cruas de I/O/driver. Diagnostics de schema mantêm código
estável e linha:coluna, com sugestão segura. O renderer detalhado da biblioteca
continua disponível a consumidores que controlam a exposição do source; a CLI
não o usa porque uma linha do schema pode conter uma password. Esta redacção é
uma alteração deliberada da apresentação, não da sintaxe nem dos exit codes.

Exemplos: `TUPREL-CLI-READ` pede verificar path/UTF-8/permissões;
`TUPREL-CLI-INIT` pede inspeccionar a pasta existente/parcial;
`TUPREL-CLI-GENERATE` pede rever ownership/ficheiros editados. Sem stack trace
normal. Mensagens técnicas são em inglês nesta versão; docs em português.

## Distribuição de desenvolvimento existente

O plugin application já produz scripts `bin/tuprel` e `bin/tuprel.bat`, com os
JARs em `lib/`. `:tuprel-cli:installDist` cria uma instalação local; não publica
nem modifica PATH. O utilizador pode invocar o path absoluto ou adicionar esse
`bin` ao seu PATH por decisão própria. Requer JAVA_HOME/JDK 21; não necessita de
conhecer classes Java internas. Distribuição assinada, instalador e política de
releases continuam fora desta entrega.

PowerShell, da raiz do repositório, com JDK 21 seleccionado (o `.env` local continua só nos testes):

```powershell
.\gradlew.bat :tuprel-migrate:test :tuprel-cli:test :tuprel-codegen-java:test :tuprel-cli:installDist --dependency-verification strict
$tuprelCli = (Resolve-Path '.\tuprel-cli\build\install\tuprel\bin\tuprel.bat').Path
$tuprelDemo = Join-Path ([IO.Path]::GetTempPath()) ('tuprel-demo-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tuprelDemo | Out-Null
Push-Location $tuprelDemo
try {
    & $tuprelCli --help
    & $tuprelCli --version
    & $tuprelCli init --dry-run
    & $tuprelCli init
    & $tuprelCli migrate create --help
    & $tuprelCli migrate create --name initial --dry-run
    & $tuprelCli migrate create --name initial
    & $tuprelCli validate
    & $tuprelCli format --check
    & $tuprelCli generate --dry-run
    & $tuprelCli generate
    & $tuprelCli generate --check
} finally { Pop-Location }
# Voltar à raiz e executar por esta ordem, nunca em paralelo:
.\gradlew.bat build --dependency-verification strict -PtuprelTestDatabase=local
.\gradlew.bat -p examples/tuprel-playground build --dependency-verification strict
```

Bash, JDK 21 já seleccionado:

```bash
./gradlew :tuprel-cli:installDist --dependency-verification strict
tuprel_cli="$PWD/tuprel-cli/build/install/tuprel/bin/tuprel"
tuprel_demo="$(mktemp -d)"
(
  cd "$tuprel_demo" || exit 2
  "$tuprel_cli" --help && "$tuprel_cli" --version &&
  "$tuprel_cli" init --dry-run && "$tuprel_cli" init &&
  "$tuprel_cli" validate && "$tuprel_cli" format --check &&
  "$tuprel_cli" generate --dry-run && "$tuprel_cli" generate &&
  "$tuprel_cli" generate --check &&
  "$tuprel_cli" migrate create --help &&
  "$tuprel_cli" migrate create --name initial --dry-run &&
  "$tuprel_cli" migrate create --name initial
)
```

## Roadmap dos comandos (não disponíveis no help actual)

| Comando | Entrega/contrato proposto |
|---|---|
| `migrate diff` | F6-004 preview conhecido; F6-005 comparação ao catálogo, desconhecidos não são sucesso |
| `migrate dev --name add_products` | F6-005; preview e confirmação vinculada ao plano antes de aplicação permitida; sem reset implícito |
| `migrate history` | depois do histórico aprovado; apresentação read-only, sem lógica paralela a status |
| `doctor` | Fase 7; checks úteis de Java/projecto/config e diagnóstico redigido; não corrigir configs automaticamente |
| `db seed` | Fase 7; execução explícita de código da aplicação, sem seed automático em deploy |
| `db pull` | Fase 8, introspecção; não antecipar com um comando vazio |
| `explain` | Fase 9, diagnósticos; sem executar ANALYZE com efeitos por omissão |
| `migrate reset` | fora do MVP; desenvolvimento inequívoco, confirmação forte do target e protecção contra produção |

Create é uma subtarefa offline separada de status para não misturar escrita de
ficheiros e ligação DB no mesmo passo. Draft sem statements conhecidos não é
deployable. History não exige nova fonte de verdade além do reader aprovado.
Não acrescentar `db push`, `db execute`, watch, studio ou resolve para ocupar
o help: exigem casos reais, contratos e gates próprios.

Para DB: produção usa configuração própria (ADR-0013), sem reutilizar o loader
de `.env` dos testes. Help por subcomando e flags só aparecem quando funcionais.
Status e o diff futuro são observações read-only, sem bootstrap de histórico.
Dry-run de deploy usa o advisory lock da inspecção e não escreve; informa que
o estado pode mudar antes da aplicação e a aprovação será revalidada sob lock. Operações destrutivas ou
desconhecidas não são desbloqueadas por um `--yes` genérico. Reset fica ausente.

## Arquitectura e compatibilidade

CLI orquestra APIs tipadas; não faz parsing de schema, geração, diff, history ou
JDBC. O grafo consta do ADR-0012/0013 e de MODULE_BOUNDARIES. Não há dependência
de JPA, Hibernate ou framework CLI. O novo módulo de ficheiros usa só java.base.

Java 21 mantém o baseline e o bytecode. Windows/PowerShell é validado localmente;
Linux/Bash e testes de symlink são gates da CI. macOS deve entrar na matriz de
consolidação F6-006; não declarar essa execução realizada numa máquina Windows.
Paths canónicos, ASCII para identidades e UTF-8/LF tornam explícitas as diferenças
de filesystem/encoding. No Windows sem privilégio de symlink há skip reportado;
o mesmo teste tem de executar no Linux, onde um skip falha a CI.
