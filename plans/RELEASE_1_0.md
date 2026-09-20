# Release Gate - Tuprel 1.0

Nenhum item é presumido. Se não foi verificado, marca como não verificado.

## Produto

- funcionalidades 1.0 em `SUPPORTED_FEATURES.md` implementadas ou explicitamente removidas do scope;
- sem feature experimental apresentada como estável;
- CLI e schema language documentados.

## Compatibilidade

- Java baseline testado;
- PostgreSQL matrix testada;
- Gradle e Maven quickstarts compilam;
- API compatibility report revisto;
- generated code compatibility definida.

## Dados e migrations

- clean install e upgrades testados;
- concurrency lock testado;
- checksums/drift testados;
- destructive changes testadas;
- recovery documentation disponível.

## Segurança

- threat model actualizado;
- injection suite passa;
- secret redaction passa;
- dependency review sem blockers;
- code scanning sem blockers críticos;
- dependency verification activo;
- nenhuma credencial em repo/history recente de release.

## Qualidade

- `./gradlew check` passa;
- integration tests passam;
- docs snippets validados;
- JavaDoc gera sem erros;
- benchmarks comparados com baseline onde aplicável.

## Release engineering

- nome e licença resolvidos;
- Maven coordinates definitivas;
- artefactos source/javadoc presentes;
- assinatura configurada;
- release notes/changelog;
- SBOM/proveniência conforme política aprovada;
- publicação testada em staging sem usar credenciais locais expostas.
