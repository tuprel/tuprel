# Supply Chain Security

## Gradle

Antes da primeira release pública:

- proibir versões dinâmicas em dependências de produção;
- activar dependency locking nas configurações relevantes;
- versionar lockfiles;
- gerar `gradle/verification-metadata.xml` com SHA-256 e, quando disponível, verificação de assinaturas;
- rever manualmente metadata bootstrap antes de confiar nela;
- manter wrapper versionado e validar actualizações;
- minimizar plugins de build e dependências transitivas.

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

Configurar:

- Dependabot;
- dependency review em pull requests;
- CodeQL/code scanning para Java/Kotlin e linguagens adicionais usadas;
- branch protection/rulesets;
- revisão obrigatória para ficheiros de release/security;
- permissões mínimas em workflows;
- actions de terceiros fixadas/pinned segundo a política do projecto.

## Release

A pipeline de publicação futura deve incluir assinatura de artefactos, hashes, source/javadoc jars, SBOM quando aprovado, proveniência e nenhuma secret em logs. Publicação só depois de name/license/Maven coordinates estarem resolvidos.
