# Supply Chain Security

## Gradle

Antes da primeira release pública:

- proibir versões dinâmicas em dependências de produção;
- ~~activar dependency locking nas configurações relevantes~~ — feito, ver
  "Estado do dependency locking" abaixo;
- ~~versionar lockfiles~~ — feito;
- ~~gerar `gradle/verification-metadata.xml` com SHA-256~~ — feito, ver "Estado
  da dependency verification" abaixo; verificação de assinaturas continua por
  decidir;
- rever manualmente metadata bootstrap antes de confiar nela;
- manter wrapper versionado e validar actualizações;
- minimizar plugins de build e dependências transitivas.

## Estado do dependency locking

Dependency locking nativo do Gradle **activo**. Lockfiles versionados:
`buildscript-gradle.lockfile` e `settings-gradle.lockfile` na raiz,
`build-logic/buildscript-gradle.lockfile` e `build-logic/gradle.lockfile` no
included build. O procedimento de refrescamento está em
`docs/development/BUILD_AND_TEST.md`.

Cobertura relevante para esta política:

- artefactos de plugin resolvidos do Gradle Plugin Portal ficam travados,
  porque entram nos classpaths de buildscript e, no caso do plugin de
  integração Error Prone, numa configuração de projecto normal de build-logic;
- dependências de Maven Central usadas por build-logic ficam travadas;
- `com.google.errorprone:error_prone_core` ainda não aparece em nenhum lock
  state: é declarado pelo convention plugin para os módulos consumidores e
  nenhum módulo de produto existe ainda. Fica travado quando o primeiro módulo
  aplicar `tuprel.java-conventions`.

Limite desta garantia: locking fixa versões seleccionadas, não integridade de
artefactos. Um artefacto substituído com as mesmas coordenadas não é detectado
por locking. Essa é a função da dependency verification, activa em modo
`strict` com checksums SHA-256 nos scopes da raiz, build-logic directo e
fixtures TestKit. A verificação de assinaturas permanece desactivada como
hardening futuro.

## Estado da dependency verification

Dependency verification nativa do Gradle **activa**, em modo `strict` (o modo
por omissão), com checksums **SHA-256**. O procedimento de refrescamento está
em `docs/development/BUILD_AND_TEST.md`.

| Ficheiro | Scope |
|---|---|
| `gradle/verification-metadata.xml` | build da raiz; governa toda a árvore, incluindo o included build |
| `build-logic/gradle/verification-metadata.xml` | invocações directas `-p build-logic` |
| `build-logic/src/test/resources/testkit/verification-metadata.xml` | baseline do grafo consumidor, copiada para cada fixture TestKit |

Configuração activa: `verify-metadata` **true** (POMs e module metadata também
são verificados), `verify-signatures` **false**. Não existem `trusted-artifacts`,
`trusted-keys`, `ignored-keys` nem regras de confiança por wildcard: todos os
artefactos são verificados por checksum explícito.

### Assinaturas

A verificação PGP de assinaturas **não** está activada. A política acima pede
assinaturas "quando disponível"; activá-las implica escolher chaves confiáveis,
lidar com dependência de keyservers e decidir o comportamento para artefactos
não assinados. É uma decisão própria, não um efeito lateral desta fatia.

### Bootstrap e trust on first use

Os checksums foram gerados por `--write-verification-metadata sha256`. Isso
regista o que estava nos repositórios nesse momento; **não** prova que o
artefacto descarregado pela primeira vez era legítimo. A garantia obtida é
detecção de alteração a partir desse ponto.

Consequência prática para revisão: um checksum novo ou alterado sem uma
mudança de versão correspondente é suspeito e tem de ser explicado antes de
ser aceite.

### Builds aninhadas de TestKit

As builds que o Gradle TestKit arranca nos testes de `build-logic` são builds
independentes e não herdam a metadata deste repositório. São cobertas por uma
baseline própria do grafo consumidor, copiada para cada fixture antes da
execução, com `--dependency-verification strict` em todas as invocações do
`GradleRunner`. Essa baseline cobre `com.google.errorprone:error_prone_core` e
o grafo JUnit que o convention plugin injecta, e foi gerada a partir de uma
build real que aplica `tuprel.java-conventions`.

Um teste dedicado prova que a verificação está activa nesse scope: uma fixture
que peça um artefacto fora da baseline falha a build.

`error_prone_core` continua ausente do **lock state** — locking e verification
são controlos distintos, e nenhum módulo de produto aplica ainda o convention
plugin. Passa a estar coberto por checksum através da baseline de TestKit.

### Escopo da revisão de bootstrap

Os checksums de componentes directos foram comparados com os checksums
SHA-256 oficiais publicados pelo Maven Central, obtidos por rede e não a partir
da cache local do Gradle. Detalhe da amostra e das limitações no relatório da
fatia; o resumo é:

- `error_prone_core`, `spotless-plugin-gradle`, `junit-bom` e
  `junit-jupiter-api` conferem com o valor publicado;
- `net.ltgt.gradle:gradle-errorprone-plugin` não tem SHA-256 oficial
  publicado (o Gradle Plugin Portal publica apenas MD5/SHA-1 para este
  artefacto e ele não existe no Maven Central), por isso não foi possível
  comparação independente;
- os restantes componentes são transitivos e não foram comparados um a um.

## Origens de artefactos actualmente em uso

Um included build resolve dependências e plugins de forma independente da
raiz. Inventário actual, necessário para a futura `verification-metadata.xml`:

| Build | Origem | Porquê |
|---|---|---|
| raiz | `mavenCentral()` | dependências declaradas no catálogo de versões |
| raiz | Gradle Plugin Portal (default do `pluginManagement`) | plugin Spotless |
| `build-logic` | `mavenCentral()` | Kotlin stdlib do `kotlin-dsl` e JUnit |
| `build-logic` | Gradle Plugin Portal | `net.ltgt.gradle:gradle-errorprone-plugin`, que não está publicado no Maven Central |
| módulos que apliquem `tuprel.java-conventions` | `mavenCentral()` | `error_prone_core` e JUnit, declarados pelo convention plugin |

O convention plugin não declara repositórios: a escolha de origens continua a
ser decisão do build que o aplica.

## GitHub

A automação de GitHub está configurada no repositório, mas **não executada**:
não existe remote e nenhum workflow foi enviado para GitHub.

| Controlo | Ficheiro | Política |
|---|---|---|
| build e quality gates | `.github/workflows/ci.yml` | Java 21, Wrapper, verification estrita, `contents: read` |
| dependency review | `.github/workflows/dependency-review.yml` | pull requests para `main`, falha em severidade alta, sem permissão de escrita |
| code scanning | `.github/workflows/codeql.yml` | Java/Kotlin; apenas o job de análise recebe `security-events: write` |
| actualizações | `.github/dependabot.yml` | Gradle na raiz e build-logic, mais GitHub Actions |

Todas as actions estão fixadas a commit SHA completo, com a versão imutável
revista indicada em comentário. `actions/setup-java` não activa cache Gradle;
`gradle/actions/setup-gradle` é o único mecanismo de cache do Gradle nos
workflows e valida também o Wrapper.

Branch protection/rulesets e revisão obrigatória de ficheiros sensíveis exigem
um repositório remoto e permanecem configuração manual para quando esse
repositório existir. Nenhum secret é necessário pelos workflows actuais.

## Release

A pipeline de publicação futura deve incluir assinatura de artefactos, hashes, source/javadoc jars, SBOM quando aprovado, proveniência e nenhuma secret em logs. Publicação só depois de name/license/Maven coordinates estarem resolvidos.
