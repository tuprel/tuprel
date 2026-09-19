# Fase 0 - Fundação de Engenharia

## Objectivo

Criar um repositório que consiga crescer sem reescrita caótica antes de implementar comportamento de ORM.

## Entregáveis

- Gradle multi-project/Kotlin DSL e wrapper;
- Java toolchain 21;
- módulos mínimos aprovados;
- package namespace provisório claramente marcado;
- formatting e static analysis;
- JUnit 5;
- estrutura para integration tests com Testcontainers;
- CI Linux e matriz Java definida;
- Dependabot, dependency review e CodeQL quando aplicável;
- dependency locking;
- dependency verification bootstrapped e revisto;
- changelog/versioning baseline;
- documentação de build/test;
- smoke test por módulo criado.

## Não fazer

- parser real;
- query DSL;
- JDBC runtime;
- migrations;
- Spring integration;
- Studio.

## Critérios de saída

- clone limpo consegue executar build documentada;
- `./gradlew check` passa;
- nenhum módulo tem dependência cíclica;
- dependency graph é revisto;
- CI usa permissões mínimas;
- não há secrets;
- Claude settings passam `claude doctor` no ambiente do utilizador;
- ADRs da fundação estão coerentes.
