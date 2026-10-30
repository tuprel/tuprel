# Compatibility Matrix

Estado: a validar durante as fases de implementação.

## Java

- Java 21: baseline obrigatório.
- CI da Fase 0: Linux com Java 21, a única versão actualmente declarada.
- JDKs posteriores: só entram na matriz depois de existir código de produto e
  uma política de compatibilidade que defina o valor desse sinal adicional.

O projecto não deve usar APIs de JDK posterior se o artefacto 1.0 declarar Java 21 como mínimo.

F6-001 mantém Java 21. Fundação CLI testada em PowerShell/Windows local e
Bash/Linux na CI, incluindo launcher application real e gates de symlink.
macOS não foi executado na máquina Windows; incluir na matriz F6-006 antes de
declarar cobertura. IDs são ASCII, SQL é UTF-8/LF e roots do catálogo canónicas.

## PostgreSQL

A matriz exacta de versões suportadas será fixada antes de 1.0 com base em versões ainda suportadas pelo PostgreSQL e cobertura real de CI.

Não declarar uma versão suportada sem integration tests relevantes.

As suites das Fases 3 a 5 exercitam PostgreSQL `17.11` via a imagem de teste
`postgres:17.11-trixie`. Isto é uma baseline de integração para o CRUD, a
query API, relações e transacções geradas, não uma promessa de suporte para
todas as versões 17.x nem
uma matriz 1.0.

## Spring Boot

A matriz de versões será definida na fase de integração. O core não depende dessa matriz.

## Gradle/Maven

Plugins devem declarar e testar versões mínimas/máximas razoáveis antes de 1.0. O runtime Tuprel não depende do build tool usado pela aplicação.
