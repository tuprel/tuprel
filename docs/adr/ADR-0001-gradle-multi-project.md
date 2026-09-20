# ADR-0001-gradle-multi-project: Gradle multi-project como build do próprio Tuprel

Estado: Aceite

## Contexto e decisão

O repositório do Tuprel será construído como Gradle multi-project com Kotlin DSL. Gradle será o build interno, mesmo existindo posteriormente integração para utilizadores Maven.

Razões: gestão central de módulos, toolchains, convention plugins, testes e publicação. Isto não obriga consumidores do ORM a usar Gradle.

## Consequências

Esta decisão deve ser reflectida na implementação, testes e documentação. Alterações significativas exigem novo ADR que substitua explicitamente este quando aplicável.
