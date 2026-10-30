# TUPREL CLI — Produto, arquitectura e contrato v1

Estado: contrato proposto para aprovação na F6-001; a tabela distingue código
entregue de roadmap. Java 21. Sem publicação, instalador ou integração automática
com frameworks nesta entrega. A CLI é uma superfície principal do toolkit.

## Experiência implementada

```text
tuprel --help
tuprel --version
tuprel init [--dry-run]
tuprel validate [--schema <ficheiro> | schema.tuprel]
tuprel format [--check | --dry-run] [--schema <ficheiro> | schema.tuprel]
tuprel generate [--check | --dry-run] [--schema <ficheiro> | schema.tuprel]
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

## Contratos do terminal

| Aspecto | Contrato F6-001 |
|---|---|
| stdout | ajuda, versão, sucesso e resumo do dry-run |
| stderr | códigos de erro/diagnostics e instrução de correcção |
| Avisos | prefixo `WARNING:` em stderr quando existirem; F6-001 não emite avisos operacionais de DB |
| 0 | operação/preview concluído com sucesso |
| 1 | schema inválido, geração não suportada ou diferenças em `--check` |
| 2 | uso inválido, I/O, path inseguro ou recusa de overwrite/ownership |
| Cores | texto simples, sem ANSI, funciona com NO_COLOR e redirecção |
| Interacção | nenhum prompt nem leitura de stdin; comportamento igual em CI |
| Versão | `tuprel development` enquanto não existir versão de release; versão real do manifest quando atribuída |
| Encoding | entrada schema UTF-8; stdout/stderr UTF-8; paths com espaços são um único argumento quoted |

`--dry-run` calcula/valida sem escrever; 0 quando o preview é válido, mesmo que
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

PowerShell, da raiz do repositório (o `.env` local continua só nos testes):

```powershell
$env:JAVA_HOME = 'C:\Users\mamad\.jdks\jdk-21.0.13+9'
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
  "$tuprel_cli" generate --check
)
```

## Roadmap dos comandos (não disponíveis no help actual)

| Comando | Entrega/contrato proposto |
|---|---|
| `migrate create --name initial` | F6-002A offline, após aprovação do formato; ID UTC único, draft versionado, sem SQL aplicado |
| `migrate status` | F6-002B; histórico read-only, pending/removed/modified; drift ainda explicitamente não verificado |
| `migrate deploy` | F6-003, só após ADRs e testes de falha/concurrency; aplica ficheiros aprovados, nunca gera DDL |
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
Status/diff são observações read-only, sem bootstrap de histórico. Dry-run de
deploy não adquire locks nem escreve; informa que o estado pode mudar antes da
aplicação e a aprovação terá de ser revalidada sob lock. Operações destrutivas ou
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
