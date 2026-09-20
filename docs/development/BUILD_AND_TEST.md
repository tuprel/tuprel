# Build e Testes

Este é o documento canónico sobre como construir e verificar o repositório.
Descreve o estado **actual** da Fase 0. Nada aqui descreve funcionalidade
planeada como se já existisse.

Para a estratégia de testes do produto (unit, golden, integração PostgreSQL,
migrations, adversarial), consulta `docs/testing/TEST_STRATEGY.md`. Este
documento trata apenas do que a build faz hoje.

---

## 1. Pré-requisitos

| Requisito | Estado |
|---|---|
| JDK 21 | Obrigatório (ver `docs/adr/ADR-0002-java-21-baseline.md`) |
| Gradle instalado globalmente | Não é necessário e não deve ser usado |
| Docker | Não é necessário nesta fase |
| PostgreSQL | Não é necessário nesta fase |
| Maven | Não é necessário nesta fase |
| Spring Boot | Não é necessário nesta fase |

O repositório traz o Gradle Wrapper versionado (`gradlew`, `gradlew.bat`,
`gradle/wrapper/`), que obtém e usa a versão de Gradle fixada pelo projecto.
Todos os comandos oficiais são executados através do Wrapper.

Basta que o Wrapper consiga encontrar um JDK 21 — tipicamente porque é o Java
activo no `PATH` ou porque `JAVA_HOME` aponta para ele. Não é preciso definir
`JAVA_HOME` se o Java activo já for a instalação correcta.

Confirma o que a build está de facto a usar:

```powershell
# Windows (PowerShell)
.\gradlew.bat --version
```

```bash
# Linux / macOS
./gradlew --version
```

A saída indica a versão do Gradle e a JVM do launcher. Se a JVM não for 21,
corrige a instalação de Java antes de continuar.

> O convention plugin `tuprel.java-conventions` pede uma toolchain Java 21. Não
> está configurado auto-provisioning de toolchains, por isso um JDK 21 tem de
> estar instalado e detectável pelo Gradle.

---

## 2. Comandos canónicos

| Objectivo | Windows (PowerShell) | Linux / macOS |
|---|---|---|
| Versões de Gradle/Java | `.\gradlew.bat --version` | `./gradlew --version` |
| Estrutura de projectos | `.\gradlew.bat projects` | `./gradlew projects` |
| Verificar formatação | `.\gradlew.bat spotlessCheck` | `./gradlew spotlessCheck` |
| Verificação completa | `.\gradlew.bat check` | `./gradlew check` |
| Build completa | `.\gradlew.bat build` | `./gradlew build` |

Usa sempre `.\gradlew.bat` ou `./gradlew`. Um `gradle` instalado globalmente
pode ter outra versão e produzir resultados que não correspondem ao projecto.

---

## 3. O que cada comando faz hoje

### `projects`

Mostra a estrutura da build:

```text
Root project 'tuprel'

Project hierarchy:

Root project 'tuprel'
No sub-projects

Included builds:

\--- Included build ':build-logic'
```

**"No sub-projects" é o resultado esperado.** A Fase 0 constrói fundação de
engenharia; ainda não existe nenhum módulo de produto Tuprel. Os módulos
planeados estão descritos em `docs/architecture/MODULE_BOUNDARIES.md` e só são
declarados quando existir código real para suportar.

`build-logic` aparece como *included build*, não como subprojecto. Ver a
secção 4.

### `spotlessCheck`

Verifica higiene de texto determinística sobre um conjunto deliberadamente
restrito de ficheiros de build e configuração:

- `settings.gradle.kts` e `build.gradle.kts` da raiz;
- `build-logic/settings.gradle.kts` e `build-logic/build.gradle.kts`;
- `build-logic/src/main/kotlin/*.gradle.kts`;
- `gradle/libs.versions.toml`;
- `gradle.properties` e `build-logic/gradle.properties`;
- workflows em `.github/` (alvo declarado; os ficheiros ainda não existem).

As verificações são apenas: converter tabulações iniciais em espaços, remover
espaços no fim das linhas e garantir newline final.

O que `spotlessCheck` **não** faz:

- não reformata o corpus de documentação (`docs/`, `plans/`, `project/`,
  `templates/`, Markdown na raiz) — Markdown está fora do âmbito por decisão
  explícita;
- não aplica nenhum motor de formatação de Java ou Kotlin (ktlint, ktfmt,
  google-java-format, palantir-java-format);
- não instala Git hooks.

Para corrigir automaticamente as violações dentro deste âmbito:

```powershell
.\gradlew.bat spotlessApply
```

```bash
./gradlew spotlessApply
```

### `check`

É o comando de verificação a executar antes de considerar trabalho concluído
(ver `docs/development/DEFINITION_OF_DONE.md`).

Na raiz, `check` agrega explicitamente a verificação do included build:

```kotlin
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
```

Isto é necessário: um included build **não** é verificado só por estar
incluído. Sem esta ligação, `check` passaria sem nunca compilar nem testar os
convention plugins.

Hoje, `check` na raiz executa:

- `spotlessCheck`;
- `build-logic:compileKotlin` e `build-logic:compileTestKotlin`;
- `build-logic:validatePlugins`;
- `build-logic:test` (os testes TestKit do convention plugin);
- `build-logic:check`.

Podes confirmar o grafo sem executar nada:

```powershell
.\gradlew.bat check --dry-run
```

```bash
./gradlew check --dry-run
```

### `build`

Executa o lifecycle `build` da raiz, que com o plugin `base` significa
`assemble` + `check`. Como não existem módulos de produto, `build` não produz
artefactos Tuprel: o seu valor actual é garantir que a verificação completa
passa.

---

## 4. `build-logic`

`build-logic` é um **included build**, não um subprojecto de produto. Contém os
convention plugins e nenhum código de produto.

Fica registado em `pluginManagement` no `settings.gradle.kts` da raiz, que é a
partir de onde os futuros módulos resolvem o plugin pelo id, sem coordenadas
nem versão.

O fluxo normal é executar `check` na raiz, que já inclui `build-logic:check`.
Durante trabalho focado no próprio convention plugin, podes executar os testes
directamente, sempre através do Wrapper da raiz:

```powershell
.\gradlew.bat -p build-logic test
```

```bash
./gradlew -p build-logic test
```

Isto é um atalho de iteração, não um substituto da verificação na raiz.

---

## 5. Baseline de `tuprel.java-conventions`

O convention plugin estabelece o que os futuros módulos Java do Tuprel vão
herdar. **Nenhum módulo o aplica hoje**, porque ainda não existe código de
produto; o comportamento descrito abaixo é verificado por testes TestKit que
aplicam o plugin a projectos temporários.

| Área | Configuração actual |
|---|---|
| Plugin Java | `java-library` |
| Toolchain | Java 21 |
| Target de compilação | `options.release = 21` |
| Encoding | UTF-8 |
| Lint do compilador | `-Xlint:all` (nenhuma categoria desactivada) |
| Warnings | `-Werror` (warning do javac falha a compilação) |
| Static analysis | Error Prone, com `disableWarningsInGeneratedCode` |
| Testes unitários | JUnit 5 sobre JUnit Platform |
| Testes de integração | source set e task `integrationTest`, sobre JUnit Platform |
| Artefactos | reproduzíveis: sem timestamps preservados, ordem de ficheiros estável |

O id do plugin é `tuprel.java-conventions`.

### Ferramentas deliberadamente **não** activas

- **NullAway: adiado.** Fica pendente até estarem decididas as semânticas de
  nulabilidade do Tuprel e as decisões de RFC associadas. Não está configurado
  nem activo.
- **Checkstyle, PMD e SpotBugs:** não adoptados. Error Prone é o único
  analisador estático da baseline.
- **Spring, Jakarta Persistence, Hibernate e drivers JDBC:** não são
  dependências do convention plugin nem do core.
- **Testcontainers e Docker:** entram quando existirem integration tests
  PostgreSQL reais, não nesta fase.

---

## 6. Estrutura de testes

Layout convencionado para os módulos que vierem a aplicar
`tuprel.java-conventions`:

```text
src/test/java
src/test/resources

src/integrationTest/java
src/integrationTest/resources
```

| | Task | Plataforma | Participa no `check` |
|---|---|---|---|
| Unit tests | `test` | JUnit Platform | sim |
| Integration tests | `integrationTest` | JUnit Platform | sim |

`integrationTest` está ordenado para correr depois de `test` (`shouldRunAfter`),
o que ordena a execução sem impedir que a task seja invocada isoladamente. As
configurações `integrationTestImplementation`, `integrationTestCompileOnly` e
`integrationTestRuntimeOnly` estendem as equivalentes de `test`, e o source set
vê o output de `main`.

Nesta fase, `integrationTest` é apenas uma convenção de estrutura e lifecycle.
Não pressupõe base de dados, container nem qualquer infraestrutura externa: não
há módulos de produto e o suporte a PostgreSQL via Testcontainers pertence a
fases posteriores, conforme `docs/testing/TEST_STRATEGY.md`.

Os testes do próprio `build-logic` usam Gradle TestKit: criam projectos Java
temporários, aplicam o convention plugin e correm builds Gradle reais para
verificar comportamento (baseline de compilação, execução de testes na JUnit
Platform, participação do `integrationTest` no `check`, e que Error Prone e
`-Werror` falham de facto a build quando devem).

---

## 7. Quality gates actuais

Existem e são executados hoje:

- higiene de formatação sobre ficheiros de build/configuração (Spotless);
- lint do compilador Java com `-Xlint:all`;
- warnings tratados como erro (`-Werror`);
- static analysis com Error Prone;
- verificação do convention plugin com JUnit 5 e Gradle TestKit;
- agregação do `build-logic:check` no `check` da raiz;
- dependency locking (secção 8);
- dependency verification SHA-256 em modo `strict` (secção 9).

Ainda **não** existem, e pertencem a fatias posteriores da Fase 0
(`plans/PHASE_0_FOUNDATION.md`):

- verificação de assinaturas PGP dos artefactos;
- integração contínua (GitHub Actions);
- CodeQL / code scanning;
- Dependabot e dependency review;
- publicação, assinatura e verificações de release.

Nenhum destes deve ser descrito como activo enquanto não for implementado.

### Caches do Gradle

O configuration cache e o build cache local estão activos tanto na raiz como
em invocações directas de `build-logic`. A compatibilidade foi validada com
Gradle 9.7.1 e dependency verification estrita:

- `check` na raiz armazenou e reutilizou a configuração sem problemas;
- `check` directo de build-logic armazenou e reutilizou a configuração sem
  problemas;
- depois de um primeiro `clean test --build-cache`, uma segunda execução
  restaurou oito tasks do cache, incluindo `test`.

`org.gradle.configuration-cache.problems=fail` impede que incompatibilidades
novas sejam aceites como warnings. O build cache configurado é local; não há
infraestrutura de cache remoto. Os testes TestKit continuam a ser a prova do
comportamento das builds consumidoras e não perdem nenhum gate de qualidade.

As origens de artefactos que a build usa actualmente estão inventariadas em
`docs/security/SUPPLY_CHAIN.md`. Esse é o documento canónico sobre supply
chain; não dupliques essa informação aqui.

---

## 8. Dependency locking

O dependency locking nativo do Gradle está **activo**. Builds normais resolvem
contra o lock state versionado, por isso a mesma revisão do repositório resolve
sempre as mesmas versões.

### O que está travado

| Lockfile | Build | Cobre |
|---|---|---|
| `buildscript-gradle.lockfile` | raiz | classpath de plugins da raiz (Spotless e grafo transitivo) |
| `build-logic/buildscript-gradle.lockfile` | `build-logic` | classpath de plugins de build-logic (`kotlin-dsl` e o grafo do Kotlin) |
| `build-logic/gradle.lockfile` | `build-logic` | configurações de projecto de build-logic (plugin Error Prone, JUnit, TestKit, compilador Kotlin) |
| `settings-gradle.lockfile` | raiz | gerado pelo Gradle para o version catalog; não contém módulos |

O projecto raiz **não** tem `gradle.lockfile` porque não tem nenhuma
configuração de dependências de projecto — `.\gradlew.bat dependencies` responde
`No configurations`. Não é uma omissão: não há nada para travar.

### Builds normais

Não passes `--write-locks` no trabalho do dia-a-dia. Os comandos da secção 2
usam o lock state existente e falham se uma versão resolvida não corresponder:

```text
Dependency version enforced by Dependency Locking
```

### Refrescar os locks deliberadamente

Actualizar locks é uma alteração de dependências e deve ser revista como tal,
não um passo de rotina para fazer uma build passar. Depois de mudar uma versão
declarada:

```powershell
# Windows (PowerShell)
.\gradlew.bat check --write-locks
```

```bash
# Linux / macOS
./gradlew check --write-locks
```

Usa `check` e não uma task que não resolva nada. O lock state de um included
build só é escrito quando alguma task resolve mesmo as suas configurações: como
o `check` da raiz agrega `build-logic:check`, um único comando cobre os dois
builds. Uma task que não toque em build-logic (por exemplo `spotlessCheck`)
reescreve apenas os classpaths de plugins.

Depois de refrescar, revê o diff dos lockfiles como reveria qualquer outra
alteração de dependências, e nunca edites versões à mão dentro de um lockfile.

### O que o locking não faz

Dependency locking fixa **as versões seleccionadas**. Não diz nada sobre o
conteúdo dos artefactos: não deteta um artefacto substituído ou adulterado que
mantenha as mesmas coordenadas.

Essa garantia é a **dependency verification**, actualmente activa em modo
`strict` com checksums SHA-256 nos scopes da raiz, build-logic directo e
fixtures TestKit. A verificação de assinaturas permanece desactivada e é
hardening futuro. Não descrevas locking como protecção de integridade.

---

## 9. Dependency verification

A dependency verification nativa do Gradle está **activa** em modo `strict`
(o modo por omissão). Cada artefacto externo resolvido é comparado com um
checksum SHA-256 registado em metadata versionada; se não corresponder, ou se
o artefacto não estiver registado, a build falha.

Três controlos diferentes, três perguntas diferentes:

| Controlo | Pergunta a que responde | Estado |
|---|---|---|
| Dependency locking | *que versão* é seleccionada | activo |
| Verificação por checksum | se os *bytes* do artefacto continuam iguais à baseline revista | activo |
| Verificação de assinaturas | quem *publicou* o artefacto (provenance) | **não activo** |

Um checksum não identifica o publicador. Diz apenas que o artefacto não mudou
desde que a baseline foi registada.

### Ficheiros de metadata

| Ficheiro | Build que o usa |
|---|---|
| `gradle/verification-metadata.xml` | invocações a partir da raiz (`.\gradlew.bat check`, `build`, ...) |
| `build-logic/gradle/verification-metadata.xml` | invocações directas com `-p build-logic` |
| `build-logic/src/test/resources/testkit/verification-metadata.xml` | builds aninhadas de TestKit (ver mais abaixo) |

São necessários os dois porque a verificação é lida a partir da build
*corrente*. Numa invocação a partir da raiz é o ficheiro da raiz que governa
toda a árvore, incluindo o included build — o ficheiro do `build-logic` é
ignorado nesse caso. Numa invocação `-p build-logic`, o `build-logic` passa a
ser a build corrente e usa o seu próprio ficheiro; sem ele, esse atalho
correria sem verificação nenhuma.

Cobrem artefactos das duas origens em uso: Maven Central e Gradle Plugin
Portal, incluindo plugin markers.

### Builds normais

Não passes `--write-verification-metadata` no trabalho normal. Os comandos da
secção 2 já verificam, porque `strict` é o modo por omissão.

### Refrescar a metadata deliberadamente

Regenerar checksums é uma operação de dependências/segurança e deve ser
revista como tal. São precisos dois comandos, um por scope de verificação:

```powershell
# Windows (PowerShell)
.\gradlew.bat check --refresh-dependencies --write-verification-metadata sha256
.\gradlew.bat -p build-logic check --refresh-dependencies --write-verification-metadata sha256
```

```bash
# Linux / macOS
./gradlew check --refresh-dependencies --write-verification-metadata sha256
./gradlew -p build-logic check --refresh-dependencies --write-verification-metadata sha256
```

`--refresh-dependencies` não é opcional. Sem ele, o Gradle só regista o que
resolver de facto nessa execução: artefactos já em cache podem não ser
re-resolvidos e ficam de fora da metadata, produzindo um ficheiro que parece
completo e falha mais tarde numa máquina limpa.

Usa `check` e não uma task que não resolva nada, pela mesma razão descrita na
secção 8.

### Aviso de bootstrap (trust on first use)

Gerar metadata **não estabelece confiança**. O Gradle limita-se a registar o
checksum do que estiver nos repositórios nesse momento. Se um artefacto já
estivesse comprometido na primeira vez que foi descarregado, o checksum gerado
passa a legitimar o artefacto comprometido.

O que a metadata garante é **estabilidade**: a partir daí, qualquer alteração
ao conteúdo de um artefacto é detectada.

Por isso o diff da metadata é revisto como qualquer outra alteração de
dependências, e checksums novos ou alterados sem uma mudança de versão
correspondente são tratados como suspeitos até prova em contrário
(ver `docs/security/SUPPLY_CHAIN.md`).

### Builds aninhadas de TestKit

Uma build TestKit é uma build Gradle independente, num directório temporário
com o seu próprio Gradle user home: não herda nada da verificação deste
repositório. Sem tratamento explícito, cada fixture resolveria do Maven Central
sem verificação nenhuma — incluindo `com.google.errorprone:error_prone_core`,
que o convention plugin injecta e que corre dentro do compilador.

O mecanismo actual fecha isso:

| Peça | Onde |
|---|---|
| Baseline partilhada do grafo consumidor | `build-logic/src/test/resources/testkit/verification-metadata.xml` |
| Cópia para cada fixture | helper `writeSettings()`, que escreve `<fixture>/gradle/verification-metadata.xml` |
| Modo de verificação | `--dependency-verification strict` em todas as invocações do `GradleRunner` |

A baseline é um ficheiro só, partilhado por todas as fixtures, e foi gerada a
partir de uma build real que aplica `tuprel.java-conventions` e resolve o grafo
que os consumidores usam de facto (Error Prone core e o grafo JUnit), com
`--refresh-dependencies`.

Um teste dedicado prova que o mecanismo está vivo: uma fixture que peça um
artefacto fora da baseline falha com `Dependency verification failed`. Se
alguma vez a baseline deixar de cobrir o que as fixtures resolvem, os testes
falham em vez de passarem a resolver sem verificação.

Quando o grafo consumidor mudar — por exemplo ao actualizar a versão do Error
Prone no convention plugin — a baseline tem de ser regenerada a partir de uma
build real que aplique o convention plugin, e o diff revisto como qualquer
outra alteração de checksums.

---

## 10. Resolução de problemas

### Os testes de `build-logic` falham com erros de memória

Os testes do convention plugin usam Gradle TestKit, que arranca **builds Gradle
completas aninhadas**: além do daemon que corre a tua build, há o processo de
teste, o daemon da build da fixture e um worker do compilador. Numa máquina com
pouca memória disponível, um destes processos pode não arrancar, tipicamente com
mensagens do género `There is insufficient memory for the Java Runtime
Environment to continue` ou falha a reservar espaço para o heap.

As fixtures já correm com limites de memória pequenos e explícitos, definidos no
próprio código de teste. Isso é infraestrutura de build e não tem qualquer
relação com os requisitos de memória do Tuprel em runtime.

Se mesmo assim falhar, por ordem:

1. termina daemons Gradle que já não precises:

   ```powershell
   .\gradlew.bat --stop
   ```

   ```bash
   ./gradlew --stop
   ```

2. fecha aplicações que estejam a consumir memória na máquina e repete;
3. *(opcional, apenas Windows)* se tiveres WSL a correr sem estar a usá-lo, a VM
   do WSL reserva memória de forma persistente e podes desligá-la:

   ```powershell
   wsl --shutdown
   ```

   Isto é resolução de problemas do teu ambiente, não um requisito de build do
   Tuprel, e só faz sentido se não estiveres a usar WSL nesse momento.

Nunca resolvas este problema desactivando gates de qualidade. Em particular, não
desactives Error Prone nem `-Werror` para fazer a build passar.

### O comando `gradle` comporta-se de forma diferente do Wrapper

Estás a usar uma instalação global de Gradle. Usa `.\gradlew.bat` ou
`./gradlew`; a versão do projecto está fixada em
`gradle/wrapper/gradle-wrapper.properties`.

### `check` passa mas não vi os testes de `build-logic`

Se nada mudou desde a execução anterior, as tasks ficam `UP-TO-DATE`. Para
confirmar que participam no grafo:

```powershell
.\gradlew.bat check --dry-run
```

```bash
./gradlew check --dry-run
```
