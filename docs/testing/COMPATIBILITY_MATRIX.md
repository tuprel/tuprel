# Compatibility Matrix

Estado: a validar durante as fases de implementação.

## Java

- Java 21: baseline obrigatório.
- CI da Fase 0: Linux com Java 21, a única versão actualmente declarada.
- JDKs posteriores: só entram na matriz depois de existir código de produto e
  uma política de compatibilidade que defina o valor desse sinal adicional.

O projecto não deve usar APIs de JDK posterior se o artefacto 1.0 declarar Java 21 como mínimo.

## PostgreSQL

A matriz exacta de versões suportadas será fixada antes de 1.0 com base em versões ainda suportadas pelo PostgreSQL e cobertura real de CI.

Não declarar uma versão suportada sem integration tests relevantes.

A suite da Fase 3 exercita PostgreSQL `17.11` via a imagem de teste
`postgres:17.11-trixie`. Isto é uma baseline de integração para o CRUD mínimo,
não uma promessa de suporte para todas as versões 17.x nem uma matriz 1.0.

## Spring Boot

A matriz de versões será definida na fase de integração. O core não depende dessa matriz.

## Gradle/Maven

Plugins devem declarar e testar versões mínimas/máximas razoáveis antes de 1.0. O runtime Tuprel não depende do build tool usado pela aplicação.
