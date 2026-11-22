# Auditoria de compatibilidade Java LTS

Base actual `372f59ac87d767eaa4b2781214473c4f62922523`. A primeira
auditoria usou `ae3776c28c328c7f6472df1f32f832d17b9f282a`. As suas
provas permanecem identificadas abaixo. A API REST do PR #103 foi avaliada
separadamente a partir de uma cópia do HEAD
`8ee68e75be0852a32c704915dccc90709335168b`.

## Inventário e bloqueios

Os onze módulos de produção de `settings.gradle.kts` usam a convenção
Java 21, com classes major 65. O ensaio de fonte original usou javac 21
com `--release 17 -proc:none`, um módulo de cada vez e os classpaths
Gradle resolvidos na base original. A actualização repetiu a compilação
isolada para o servidor e o schema actuais. Uma aprovação
nesta coluna permite ler dependências major 65 com o compilador 21. Pode
ocultar incompatibilidades transitivas e o parser pode parar antes da
resolução de APIs. Repetir com o grafo integral major 61 antes de certificar.

| Módulo | Ensaio de fonte | Evidência e risco da adaptação |
| --- | --- | --- |
| `tuprel-schema` | Falha | Na base original, `Parser`, `SchemaFormatter`, `SemanticValidator` e `RelationGraph` usavam operações de listas sequenciadas. Na base actual, `SchemaCompletion` (linhas 71, 78, 209) e `SchemaSymbols` (linhas 99, 122, 164) acrescentam pattern switches e operações sequenciadas. Risco médio para ordem de sugestões, diagnóstico e resolução de símbolos. |
| `tuprel-language-server` | Falha | `TuprelLanguageServer:264` usa pattern switch sobre `SchemaSymbols.Symbol` para hover. O ensaio rejeita a primeira cláusula na linha 265. Os records privados e o `readNBytes` de `LspTransport:49` são compatíveis com 17. O servidor depende do schema, que também precisa de adaptação. Risco médio para dispatch, semântica das respostas e framing. |
| `tuprel-codegen-java` | Passa isoladamente | Depende de schema. `JavaCodeGeneratorTest` compila fixtures com release 21. Risco médio para tipos, goldens e ligação ao runtime. |
| `tuprel-sql` | Falha | `SqlCondition:46` chama `List.getFirst()`. Risco baixo se mantiver a validação de cardinalidade. |
| `tuprel-runtime` | Falha | `Cursor:127,172` usa pattern switch. `ModelOperations`, `RelationLoader`, `Relation` e `TuprelDatabase` usam acessos sequenciados. Risco médio a alto para bytes de cursor, ordem, cardinalidade e transacções. |
| `tuprel-postgresql` | Falha | Pattern switch em `PostgresqlRenderer` e `PostgresqlBinder`. `PostgresqlRenderer:241` usa `List.getFirst()`. Risco alto para SQL, ordem de parâmetros, binding, nulls e falhas JDBC. |
| `tuprel-migrate` | Falha | `MigrationDirectory:128` usa `reversed()`. `MigrationStatus`, `MigrationCreator` e `NativeMigrationPublisher` usam `getLast()`. `NativeRenameProcess:98` inicia uma virtual thread. Risco alto para ordem de validação, deadlines, streams bloqueados e resultado incerto do processo. |
| `tuprel-migrate-postgresql` | Falha | Pattern switch em `PostgresqlMigrationPlanCompiler`, `SchemaSnapshot` e `DdlParseResult`. Acessos sequenciados em `ManagedMigrationCommands` e `PostgresqlMigrationDevPlanner`. Risco alto para preview canónico, digests e autorização de deploy. |
| `tuprel-cli` | Passa isoladamente | O launcher falha com Java 17 ao carregar `TuprelCli`. O grafo inclui schema, codegen e os módulos de migrations. Rever também a ajuda em `TuprelCli:256`. |
| `tuprel-gradle-plugin` | Passa isoladamente | Precisa de bytecode e variante JVM Gradle compatíveis e de uma CLI executável na toolchain escolhida. TestKit cobre 9.7.1 e 9.8.2 no baseline. Risco médio para resolução, execução de JavaExec e incrementalidade. |
| `tuprel-spring-boot-starter` | Passa isoladamente | Expõe runtime e usa o adapter PostgreSQL, ambos major 65. Risco médio para classpath, auto-configuração e precedência do DataSource. |

`build-logic` é um build incluído, com toolchain Java/Kotlin 21. Error Prone
2.50.0, o plugin Gradle Error Prone 5.1.1, Spotless, JUnit e Testcontainers
pertencem à build ou aos testes. A toolchain interna pode manter-se em 21
desde que as provas do consumidor escolham explicitamente o JDK alvo.
`test-support`, os exemplos e os probes Java de `native/` também têm de
ser classificados. Um helper de teste pode continuar em 21 se nunca for
carregado pelo processo consumidor. O verificador Java do instalador e
o launcher estável fazem parte da cadeia que o utilizador executará.

## Dependências e ferramentas

| Grafo resolvido ou ferramenta | Evidência |
| --- | --- |
| CLI e PostgreSQL | pgjdbc 42.7.14 major base 52, variante Java 11 major 55. Checker Qual 3.55.1 major base 52. `jdeps --missing-deps` localizou referências opcionais do pgjdbc a OSGi e JNA/Waffle para SSPI. Estas referências não provam suporte a SSPI. Validar JDBC/TLS, SCRAM e apenas os mecanismos de autenticação anunciados. |
| Starter | Boot/autoconfigure 4.1.2 e Spring Framework 7.0.10 major base até 61. Spring Core inclui variantes 21/25-selectable em `META-INF/versions/21` e `24`, major 65 e 68. Micrometer 1.16.8, Commons Logging 1.3.5 e JSpecify 1.0.1 até major 52 no grafo auditado. |
| Runtime e SQL | Sem dependências externas no grafo de produção. O adapter PostgreSQL acrescenta o driver e as respectivas dependências. |
| Language Server | `installDist/lib` contém servidor e schema (major 65), Gson 2.15.0 e `error_prone_annotations` 2.50.0 (classes base major 52 e descritor Java 9 major 53 em ambos). O `jdeps` confirmou as dependências directas do servidor em `java.base`, Gson e schema. O Gson referencia também `java.sql` e as anotações. O Error Prone usado como processador no build fica fora da distribuição. |
| Gradle | Wrapper 9.8.2 arrancou nas três JVMs. A matriz oficial exige Gradle 9.1 ou superior para executar o daemon em Java 25. Avaliar 9.7.1 e 9.8.2 para o plugin, sem inferir suporte Gradle 8 do suporte do Spring Boot. |
| Maven | 3.9.16 arrancou nas três JVMs. O consumidor usa Maven Compiler, Exec, Build Helper, Surefire e Boot Repackage. O caminho de staging usa CLI e repositório local. Maven 4 fica fora da primeira matriz. |
| Spring Boot | A linha 4.1 suporta Java 17. O POM do #103 fixa release 21 e a aplicação empacotada falha em 17 na classe `ProductsApplication`. O limite observado nesta prova é do Tuprel/exemplo. |

Fontes de suporte externo. [Gradle](https://docs.gradle.org/9.8.2/userguide/compatibility.html)
· [Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html)
· [Maven](https://maven.apache.org/docs/history.html)
· [Gson](https://github.com/google/gson/tree/gson-parent-2.15.0#minimum-java-version).

## Provas executadas e limites

Provas originais em Windows x64. Temurin 17.0.21 e 21.0.13, Oracle JDK
25.0.5.0.3.
JDK 17 extraído num directório de auditoria e verificado com SHA-256.
`javap -verbose` confirmou major 65 na CLI. Os cabeçalhos das classes
foram inspeccionados separando a base das entradas multi-release.
`jdeps --multi-release 17` reportou dependências JDK, incluindo
`java.compiler` para codegen e `jdk.security.auth` para o executor
gerido (`ManagedExecutorPin`). Conservar estes módulos numa futura
imagem reduzida e testar também os fornecedores criptográficos e TLS.

| Superfície | Java 17 | Java 21 | Java 25 |
| --- | --- | --- | --- |
| CLI Windows, validate, generate, generate --check | Falha antes do comando, 65 > 61 | Passou | Passou |
| Migrations CLI | Mesmo bloqueio da CLI | init, create dry-run e diff help passaram | Mesmos ensaios passaram. Deploy e execução gerida por certificar |
| Código gerado real | javac 17 bloqueado pelas bibliotecas major 65 | 44 fontes do playground compilaram com javac 21 e release 17 | A API do #103 compilou e executou com JDK 25 e release 21 |
| Spring Boot, Maven e JAR API #103 | JAR recusa carregar a aplicação | HTTP/PostgreSQL e java -jar aprovados no #103 | Maven verify e prova independente java -jar passaram com PostgreSQL 17 |
| Gradle e plugin | Wrapper arranca. Plugin major 65 | TestKit existente | Wrapper arranca. Plugin no daemon 25 por certificar |
| Instalador nativo e launcher estável | Verificador de staging compilado para 21 | Provas de staging existentes por perfil | JNI, assinatura, instalação e rollback por certificar |
| Language Server, `installDist` por stdio, base actual | Falha antes de emitir frames, major 65 > 61 | Passou no Windows x64 | Passou no Windows x64 com os mesmos JARs |
| Extensão VS Code, base actual | A invocação equivalente falha ao carregar o servidor | Invocação equivalente passou, extensão por certificar | Invocação equivalente passou, extensão por certificar |

### Language Server na base actual

Construí a distribuição com JDK 21 e verificação estrita e executei os
mesmos JARs em Windows x64 nas três JVMs. Usei o launcher
`bin/tuprel-language-server.bat` e, separadamente, a invocação directa do
editor, `java.exe -cp "lib/*" dev.tuprel.lsp.TuprelLanguageServer`, com o
classpath da distribuição e sem opções JVM herdadas. Em Java 21 e 25,
cada execução devolveu oito frames LSP válidos: inicialização, diagnóstico
de tipo inválido, limpeza do diagnóstico após edição, formatação,
hover de um escalar, completion, resposta de definição para um escalar
sem localização e shutdown. O processo terminou com código 0 e stderr
vazio. Em Java 17, ambas as invocações terminaram com
`UnsupportedClassVersionError`, código diferente de zero e stdout vazio.
O ensaio directo reproduz a chamada da extensão. O host VS Code e o
launcher Unix ficam por certificar nesta matriz.

A [documentação da extensão](https://github.com/tuprel/tuprel-editors/blob/27a7e291771255254be8b438e803dc6f330ce21a/extensions/vscode/README.md)
e o [manifesto de settings](https://github.com/tuprel/tuprel-editors/blob/27a7e291771255254be8b438e803dc6f330ce21a/extensions/vscode/package.json)
pedem um executável Java 21 e os caminhos absolutos para o Java e para
a distribuição oficial. A extensão inicia o Java directamente, com pipes
stdio, num workspace local de confiança. O realce e snippets funcionam sem
Java. A configuração, os diagnósticos de arranque e o modo sintáctico em
caso de falha fazem parte da futura certificação da extensão.

`gradlew check --dependency-verification strict` passou também com o
daemon Gradle em JDK 25. O build reteve a toolchain 21 para os workers
de compilação e teste. Este resultado cobre a execução do build interno,
enquanto a prova do #103 executou o consumidor e o JAR em JDK 25.

O ensaio de geração em 21 e o bloqueio do compilador real 17 demonstram
limites diferentes. A compilação em 21 com `--release 17` verifica as APIs
JDK usadas por aquelas fontes. A leitura das dependências major 65 por
javac 21 permite o ensaio e impede uma conclusão de compatibilidade do
produto em Java 17. A fixture cobre relações, includes, BigDecimal e tipos
temporais. Acrescentar enum, tipos anuláveis, builders e updates, filtros,
transacções, goldens completos e compilação de todos os ficheiros em javac
17 após portar as bibliotecas.

O C usa APIs do SO e JNI em `native/cli-installer/staging-root-jni.c`.
O publicador nativo é um processo separado. O código C não tem bytecode
Java. A análise estática não demonstra carregamento JNI, compatibilidade do
launcher nem segurança de execução em outra JVM. Verificar Ed25519 e
SHA-256, identidade e permissões do filesystem, timeouts, interrupções,
TLS/SCRAM, update e rollback. A P0-02B.4 e a confiança de release conservam
os seus critérios independentes.

## Plano e matriz a certificar

| PR | Escopo e gate |
| --- | --- |
| A | Auditoria e ADR proposto. A aprovação precede alterações à baseline. |
| B1 | Schema e SQL. Corrigir chamadas de colecções sem alterar ordem, mensagens ou contratos. Compilar com release 17 e executar goldens. |
| B2 | Runtime e adapter PostgreSQL. Preservar dispatch exaustivo, ordem de bind, cursores e semântica de falhas. Executar SQL goldens, CRUD, relações e transacções com PostgreSQL 17. |
| B3 | Migrations, adapter e CLI. Preservar bytes, digests, confinamento e estados de falha. Testar streams simultâneos, limites, interrupção e locks antes de trocar a estratégia de threads. Provar o launcher com Java 17. |
| C | Gerador e saída Java. Comparar hashes entre JDKs, compilar com javac 17 e executar um consumidor em cada JDK. Manter protecção dos ficheiros gerados. |
| D1 | Maven, Gradle, Spring Boot e instalador em fatias próprias. Resolver artefactos de staging em projectos independentes. Testar HTTP e JAR, ownership do DataSource e ciclo de vida. Provar JNI, PATH e selecção de versões em Windows e Linux. |
| D2 | Depois do schema, adaptar o Language Server e provar `installDist`, launchers e stdio nos três JDKs. Exercitar diagnósticos, formatação, completions, hover, definição positiva e negativa, encerramento e limites de protocolo. Validar o host VS Code com o servidor instalado e a recuperação perante uma JVM incompatível. |
| E | Tornar obrigatórias as três células. Preservar CodeQL, Dependency Review, locks e verificação de dependências. Actualizar o suporte público após a certificação e a decisão explícita de activação. |

Começar com um job de produção JDK 21, com análise de API e bytecode do
grafo distribuído. Em cada célula JDK 17/21/25, consumir os mesmos hashes,
sem fallback para JDK 21. Executar os testes unitários aplicáveis, gerar
e compilar o cliente externo, executar Maven e Gradle sob o JDK da
célula e provar PostgreSQL real, migrations e java -jar. Executar Windows e
Linux para launchers, instalador e Language Server. Testar metadados adulterados, versão
incompatível, rollback permitido e negado, recuperação e JVM seleccionada.
Validar compatibilidade binária e de fontes numa actualização real do
consumidor. Qualquer célula omitida mantém o perfil por certificar.

## D1-A — consumidor Maven

A prova Maven usa a main `13cd04d8dda99ba0b337e94f34118484a8f10a3d`
como base. O harness `test-support/distribution/Verify-MavenLts.ps1`
publica uma vez os JARs SQL, runtime e PostgreSQL num repositório temporário
`dev.tuprel.staging:0.0.0-fixture`, usando a toolchain 21 e verificação
estrita de dependências. Cada célula Maven 3.9.16 tem cache próprio e usa
o Java e javac reais da versão seleccionada. O projecto e a instalação ZIP
da CLI ficam fora do checkout, em caminhos com espaços.

A execução Windows x64 e o job Linux x64 do PR #116 comprovaram geração,
compilação com release 17 e execução nas JVMs 17, 21 e 25. Os 16 ficheiros
gerados mantiveram os mesmos bytes. Os 97 classfiles dos três JARs têm
major 61. O driver PostgreSQL 42.7.14 e checker-qual 3.55.1 resolvidos pelo
Maven coincidem com os SHA-256 do manifesto Gradle. Os POMs preservam
o driver no scope runtime e os metadados Gradle declaram JVM 17.

As três células executaram também o exemplo de vendas, compilado pelo
Maven, em schemas temporários de PostgreSQL 17. A prova cobre CRUD,
filtros tipados, ordenação, paginação, relações, transacções e rollback.
O harness remove os schemas e transmite as credenciais pelo stdin do
consumidor. A fixture local usa exclusivamente `tuprel_devtest`.

O consumidor compilado com release 17 executou sem recompilação nas três
JVMs. O consumidor compilado com release 21 executou nas JVMs 21 e 25.
O teste de upgrade preserva ainda a revisão antiga fixada em
`2b9ef01baa96633efc630cae1d288c061a94256b`, incluindo regeneração após
alteração do schema. Os testes negativos recusam bytecode incompatível,
versão inexistente, checksum inválido, cache adulterada e sobrescrita de
fontes geradas editadas. A prova Linux verifica assinaturas com chaves
GnuPG efémeras e recusa bytes adulterados e assinatura substituída.

Estes resultados cobrem o consumidor Maven de staging. A activação pública
do suporte Java 17 e as restantes integrações continuam sujeitas aos gates
do ADR-0024. Os checks do HEAD final e as limitações locais são registados
na descrição do PR #116.

## D1-B — plugin Gradle

A base da entrega é `b6995c5e2b821bc3bfcb4331a8e4508a25c20834`.
O plugin original tem cinco classes major 65 e fixa Java 21 no launcher
da CLI. A adaptação compila essas classes com release 17 e conserva o
inventário, os descritores públicos/protegidos e o ID `dev.tuprel.codegen`.
O provider do launcher passa a seguir a toolchain do consumidor. Uma
toolchain vazia utiliza a JVM do build. O compilador interno mantém JDK 21.

`Verify-GradlePluginLts.ps1` publica uma vez o plugin e o marker num
repositório temporário, verifica POM, variantes JVM e SHA-256, e resolve-os
por ID e versão em projectos externos. As nove células combinam Gradle
9.7.1/9.8.2/9.9.1 com Java 17/21/25. Todas usam o mesmo artefacto publicado.
O daemon e a CLI usam a JVM da célula; o compilador de fontes geradas é
um javac 17 real. A descoberta e o download automático de JDKs ficam
desactivados. O harness verifica também uma toolchain explícita 17.

A baseline diferencial guardada antes da alteração confirma os mesmos
17 ficheiros gerados, incluindo o manifest de ownership, e os respectivos
SHA-256. A configuração de cache da fixture foi isolada por projecto para
que o primeiro `compileJava` execute realmente, mantendo o assert SUCCESS.
São testados os estados incrementais, configuration cache, mudança do
classpath, evolução do schema, clean e recusa de fontes editadas.
Artefactos e markers adulterados são recusados sob verificação estrita.

A CI acrescenta Windows e Linux, com limites de tempo próprios. No Linux,
um perfil adicional corre os três Gradles num contentor Temurin 17 fixado
por digest, sem rede e sem JDKs do host montados. Isto distingue a
restrição da descoberta de toolchains da execução efectiva num ambiente
que só contém Java 17. Os resultados por célula e os checks do HEAD final
ficam no PR funcional. A certificação pública do produto continua
dependente da activação explícita do ADR-0024.

## D1-C — starter Spring Boot e aplicação REST

Base `93942402160cbc82b2d1f169d18f6e80f23c24ef`. A única alteração de
produção é a compilação do starter com release 17 e toolchain interna 21.
A classe de auto-configuração, o seu nome e registo SPI mantêm-se. O
inventário tem uma classe; a comparação `javap -p -s` com o JAR original
preserva os descritores. O consumidor da fixture anterior foi compilado
em Java 21 antes da alteração para a prova de ligação binária em 21 e 25.

A aplicação de produtos adapta o PR #103 no commit
`8ee68e75be0852a32c704915dccc90709335168b`, sem integrar esse PR. Mantém
controllers, serviços, DTOs, schema e preparação SQL explícita. As mudanças
de portabilidade retiram `HttpClient` de try-with-resources e substituem
`getFirst()` depois da validação de cardinalidade. Não alteram o runtime,
as transacções ou a geração Java.

`Verify-SpringLts.ps1` publica uma vez SQL, runtime, adapter e starter num
repositório temporário. Cada Maven 3.9.16/JDK usa cache próprio, configuração
isolada, CLI instalada fora do checkout e compilador da JVM seleccionada
com release 17. O harness compara SHA-256 dos artefactos e dos ficheiros
gerados, verifica POM/scopes, variantes Gradle, SPI e bytecode do grafo
empacotado, incluindo as variantes multi-release seleccionáveis por Java 17.

As provas incluem HTTP real, BigDecimal, paginação ordenada, validação,
inputs SQL tratados como dados, erros redigidos e encerramento. O JAR
executável arranca depois da saída do Maven; o mesmo JAR compilado em 17
é repetido em 17, 21 e 25 sem recompilar. Os schemas têm nomes exclusivos,
são criados apenas depois de validar o alvo de testes e são removidos
depois do encerramento. O loader local mantém-se exclusivamente de testes.

A fixture comum de ownership cobre DataSources único, primário, ambíguos,
ausente e explícito, ausência de queries no arranque e fecho do pool pelo
Spring, incluindo falha de configuração. A ausência de DataSource exclui
expressamente o aprovisionamento JDBC automático do Spring, que não é uma
responsabilidade do Tuprel. A prova PostgreSQL preserva commit e rollback
Tuprel e Spring, commit independente do Tuprel dentro de uma transacção
Spring, recusa do proxy transaccional e continuidade após falha JDBC.
O ADR-0023 permanece proposto.

Windows e Linux têm jobs próprios com PostgreSQL 17 real. O Windows usa
um cluster temporário nos runners descartáveis, sem tocar no serviço do
computador do programador. Linux acrescenta um contentor Java17-only,
sem JDKs do host, com Maven offline e rede do host para o PostgreSQL local
da fixture. Resultados por célula, hashes, checks finais e limitações ficam
no PR funcional. Nenhum destes testes activa o suporte público Java 17.

O job CodeQL compila também o exemplo Maven no checkout, para que os
resultados apontem às fontes REST do PR. Usa `test-compile`, sem executar
SQL; a execução HTTP/PostgreSQL permanece nos jobs externos. Os pins,
permissões e restantes builds de análise mantêm-se.

## D1-D — Instalador e launcher

A certificação usa `Verify-InstallerLts.ps1`, com JDK 21 como produtor e
compilação release 17 dos helpers standalone. O inventário anterior contém
dez classes; acrescenta-se apenas o teste `InstallerRuntimeTest`. A API de
entrada, os descritores JNI e os formatos assinados permanecem iguais.
Não são necessárias novas dependências. Os helpers anteriores eram major
65; os novos são major 61. A implementação nativa não muda.

Uma única preparação produz duas versões CLI, um catálogo com assinatura
Ed25519 efémera, o verificador e a biblioteca JNI. Cada célula 17/21/25
reutiliza esses bytes, compara SHA-256 antes/depois e executa instalação,
activação, launcher, geração, compilação release 17, rollback, interrupções
e remoção. A JVM vem do caminho explícito seleccionado, sem fallback 21.
O preflight de classfiles recusa instalação/activação incompatível antes
de alterar a versão anterior; a remoção de dados autenticados não carrega
classes e continua possível numa JVM mais antiga.

A matriz separa Windows/NTFS, Linux/ext4 e Linux/XFS. Os perfis Linux
incluem uma prova sem rede num contentor apenas com JDK 17 e os artefactos
preparados, sem montar o checkout ou outros JDKs. Os resultados efectivos
e limitações devem ser registados no PR; a definição do job não certifica
uma célula. O ambiente Windows local sem MSVC permite compilar e verificar
assinaturas nas três JVMs, mas não executar a prova JNI completa.

Não se certificam aqui runtime incluído, distribuição pública do bootstrap
ou biblioteca nativa, metadados de release, reinício real ou alteração do
PATH real do utilizador. P0-02B.4 conserva os seus gates. D2 e a activação
E ficam fora do âmbito; Java 21 continua como mínimo público.
