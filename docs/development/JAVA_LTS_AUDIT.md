# Auditoria de compatibilidade Java LTS

Base `ae3776c28c328c7f6472df1f32f832d17b9f282a`. A API REST, ainda no
PR #103, foi avaliada separadamente a partir de uma cópia do HEAD
`8ee68e75be0852a32c704915dccc90709335168b`.

## Inventário e bloqueios

Os dez módulos publicáveis têm classes de produção major 65 sob a configuração
actual. O ensaio de fonte usou javac 21 com `--release 17 -proc:none`, um
módulo de cada vez e os classpaths Gradle resolvidos no baseline. Uma aprovação
nesta coluna permite ler dependências major 65 com o compilador 21. Pode
ocultar incompatibilidades transitivas e o parser pode parar antes da
resolução de APIs. Repetir com o grafo integral major 61 antes de certificar.

| Módulo | Ensaio de fonte | Evidência e risco da adaptação |
| --- | --- | --- |
| `tuprel-schema` | Falha | `Parser`, `SchemaFormatter`, `SemanticValidator` e `RelationGraph` usam operações de listas sequenciadas. Risco baixo a médio para ordenação e diagnósticos. |
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
| Gradle | Wrapper 9.8.2 arrancou nas três JVMs. A matriz oficial exige Gradle 9.1 ou superior para executar o daemon em Java 25. Avaliar 9.7.1 e 9.8.2 para o plugin, sem inferir suporte Gradle 8 do suporte do Spring Boot. |
| Maven | 3.9.16 arrancou nas três JVMs. O consumidor usa Maven Compiler, Exec, Build Helper, Surefire e Boot Repackage. O caminho de staging usa CLI e repositório local. Maven 4 fica fora da primeira matriz. |
| Spring Boot | A linha 4.1 suporta Java 17. O POM do #103 fixa release 21 e a aplicação empacotada falha em 17 na classe `ProductsApplication`. O limite observado nesta prova é do Tuprel/exemplo. |

Fontes de suporte externo. [Gradle](https://docs.gradle.org/9.8.2/userguide/compatibility.html)
· [Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html)
· [Maven](https://maven.apache.org/docs/history.html).

## Provas executadas e limites

Windows x64. Temurin 17.0.21 e 21.0.13, Oracle JDK 25.0.5.0.3.
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
| D | Maven, Gradle, Spring Boot e instalador em fatias próprias. Resolver artefactos de staging em projectos independentes. Testar HTTP e JAR, ownership do DataSource e ciclo de vida. Provar JNI, PATH e selecção de versões em Windows e Linux. |
| E | Tornar obrigatórias as três células e documentar o suporte comprovado. Preservar CodeQL, Dependency Review, locks e verificação de dependências. |

Começar com um job de produção JDK 21, com análise de API e bytecode do
grafo distribuído. Em cada célula JDK 17/21/25, consumir os mesmos hashes,
sem fallback para JDK 21. Executar os testes unitários aplicáveis, gerar
e compilar o cliente externo, executar Maven e Gradle sob o JDK da
célula e provar PostgreSQL real, migrations e java -jar. Executar Windows e
Linux para launchers e instalador. Testar metadados adulterados, versão
incompatível, rollback permitido e negado, recuperação e JVM seleccionada.
Validar compatibilidade binária e de fontes numa actualização real do
consumidor. Qualquer célula omitida mantém o perfil por certificar.
