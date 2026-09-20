# ADR-0007-generated-sources: Generated sources fora de src/main/java

Estado: Aceite

## Contexto e decisão

Gradle: `build/generated/sources/tuprel/main`. Maven: `target/generated-sources/tuprel`. O build integra esses caminhos como generated sources. Ficheiros gerados não são fonte manual.

## Consequências

Esta decisão deve ser reflectida na implementação, testes e documentação. Alterações significativas exigem novo ADR que substitua explicitamente este quando aplicável.
