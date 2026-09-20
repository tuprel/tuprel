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
- agregação do `build-logic:check` no `check` da raiz.

Ainda **não** existem, e pertencem a fatias posteriores da Fase 0
(`plans/PHASE_0_FOUNDATION.md`):

- dependency locking;
- dependency verification (`verification-metadata.xml`, checksums, chaves);
- integração contínua (GitHub Actions);
- CodeQL / code scanning;
- Dependabot e dependency review;
- configuration cache e build cache;
- publicação, assinatura e verificações de release.

Nenhum destes deve ser descrito como activo enquanto não for implementado.

As origens de artefactos que a build usa actualmente estão inventariadas em
`docs/security/SUPPLY_CHAIN.md`. Esse é o documento canónico sobre supply
chain; não dupliques essa informação aqui.

---

## 8. Resolução de problemas

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
