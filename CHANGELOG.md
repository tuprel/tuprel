# Changelog

Registo das alterações relevantes ao nível do projecto.

A estrutura segue os princípios do *Keep a Changelog*: secções por tipo de
alteração, entradas legíveis por quem usa o projecto e uma secção para trabalho
ainda não publicado. A política de versionamento está em
`docs/development/VERSIONING.md`.

Duas regras para este ficheiro:

- não é um registo interno passo a passo do desenvolvimento; regista
  desenvolvimentos com significado para quem vier a usar ou avaliar o projecto;
- nada é listado como disponível antes de existir.

**O Tuprel ainda não teve nenhuma release pública.** Não existe versão
publicada, artefacto distribuído nem coordenada Maven aprovada.

## Não publicado

### Adicionado

- Fundação documental do projecto: especificação de produto, princípios,
  arquitectura, module boundaries, modelo de erros, estratégia de testes,
  requisitos de segurança, threat model e ADRs da fundação.
- Java 21 como baseline de linguagem e runtime (ADR-0002).
- Build Gradle com Kotlin DSL e Gradle Wrapper versionado (ADR-0001).
- Gate de formatação determinística sobre os ficheiros de build e configuração,
  com âmbito conservador e sem motor de formatação de código.
- Included build `build-logic` com o convention plugin
  `tuprel.java-conventions`, que estabelece a baseline dos futuros módulos
  Java: toolchain e target Java 21, compilação em UTF-8, `-Xlint:all`,
  `-Werror`, Error Prone, JUnit 5 sobre JUnit Platform, source set e task
  `integrationTest`, e artefactos reproduzíveis.
- Verificação do próprio convention plugin com JUnit 5 e Gradle TestKit,
  agregada no `check` da raiz.
- Documentação operacional de build e testes em
  `docs/development/BUILD_AND_TEST.md`.
- Inventário das origens de artefactos usadas pela build em
  `docs/security/SUPPLY_CHAIN.md`.
- Dependency locking do Gradle activo, com lockfiles versionados para o
  classpath de plugins da raiz e para o included build `build-logic`. Fixa as
  versões resolvidas.
- Dependency verification do Gradle activa em modo `strict`, com checksums
  SHA-256 versionados para os três scopes de build: raiz, `build-logic` e as
  builds aninhadas de Gradle TestKit. Cobre artefactos do Maven Central e do
  Gradle Plugin Portal, incluindo plugin markers e o grafo consumidor do
  convention plugin. Verifica bytes de artefactos, não identidade de
  publicador: a verificação de assinaturas PGP continua por decidir (ver
  `docs/security/SUPPLY_CHAIN.md`).
- Configuration cache e build cache local do Gradle, activados após validação
  de armazenamento e reutilização na raiz e em build-logic.
- Automação GitHub com CI Java 21, dependency review, CodeQL e Dependabot,
  usando permissões mínimas e actions fixadas a commits imutáveis.
- Linguagem de schema da Fase 1 no módulo `tuprel-schema`, com lexer, parser,
  AST, modelo validado, diagnostics e formatter para o subconjunto aceite pelo
  RFC-001.
- Módulo `tuprel-cli` com os comandos iniciais `tuprel validate` e
  `tuprel format`.

### Alterado

- Documentação de arquitectura, fases e handoff reconciliada com o fecho da
  Fase 0: zero módulos de produto, primeiro módulo na Fase 1 e RFCs aplicados
  como gates das fases que afectam.

### Notas

- O nome de trabalho do produto é **Tuprel**. Continua a ser um nome de
  trabalho: não foi feita verificação profissional de naming/trademark e a
  publicação permanece desactivada (ver `docs/product/NAMING_AND_LEGAL.md`).
- `dev.tuprel` é uma coordenada provisória e não uma coordenada de publicação
  aprovada.
- Não existe ainda runtime ORM, geração de código, motor de migrações nem
  suporte a PostgreSQL. O roadmap por fases está em `plans/MASTER_PLAN.md`.
