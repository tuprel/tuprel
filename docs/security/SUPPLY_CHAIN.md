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
