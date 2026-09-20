# Fase 0 - Fundação de Engenharia

Estado: **Concluída localmente em 2026-09-20.** A automação GitHub está
configurada, mas ainda não foi executada porque o repositório não tem remote.

## Objectivo

Criar um repositório que consiga crescer sem reescrita caótica antes de implementar comportamento de ORM.

## Entregáveis

- fundação Gradle Kotlin DSL preparada para crescer como multi-project, com
  Wrapper e build-logic como included build;
- Java toolchain 21;
- catálogo e boundaries dos módulos aprovados, sem criar módulos de produto
  vazios; o primeiro módulo real pertence à Fase 1;
- package namespace provisório claramente marcado;
- formatting e static analysis;
- JUnit 5;
- source set/task reutilizável para integration tests; Testcontainers entra
  com o primeiro comportamento dependente de PostgreSQL;
- CI Linux e matriz Java definida;
- Dependabot, dependency review e CodeQL quando aplicável;
- dependency locking;
- dependency verification bootstrapped e revisto;
- changelog/versioning baseline;
- documentação de build/test;
- testes comportamentais do convention plugin com TestKit; smoke tests passam
  a ser obrigatórios por cada módulo de produto quando esse módulo existir.

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

## Evidência de saída

- o Wrapper 9.7.1 executa `spotlessCheck`, `check` e `build` com Java 21;
- a raiz continua sem product subprojects e agrega `build-logic:check`;
- locking e verification estrita cobrem raiz, build-logic directo e fixtures
  TestKit;
- configuration cache e build cache local foram armazenados e reutilizados
  em validação real;
- CI Linux/Java 21, dependency review, CodeQL e Dependabot estão versionados
  com permissões mínimas e actions fixadas a SHAs completos;
- Claude Code 2.1.278 executou `claude doctor` sem problemas de instalação;
- publicação, signing e credenciais continuam ausentes.
