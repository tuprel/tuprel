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

- Licença open source Apache-2.0 no `LICENSE` da raiz.
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
- Módulo `tuprel-codegen-java` com geração determinística de valores Java,
  enums, metadata tipada e inputs de create/update a partir do schema validado;
  `tuprel generate` e `generate --check` na CLI, com escrita segura e teste de
  compilação real do código gerado.
- Fundação de runtime PostgreSQL nos módulos `tuprel-sql`, `tuprel-runtime` e
  `tuprel-postgresql`: CRUD estrutural mínimo, SQL parametrizado, bindings e
  leituras escalares tipadas, lifecycle JDBC explícito e testes reais via
  Testcontainers.
- Cliente operacional gerado e query API type-safe (RFC-004, ADR-0010). Cada
  model com colunas mapeáveis recebe um cliente com:
  - `create`, `findById`, `findMany`, `findFirst` e `findManyCursor`;
  - `select`, `count` e `exists`;
  - `updateById`, `deleteById` e `preview`.

  Cada método executa um único statement parametrizado. Colunas tipadas por
  model garantem, em compilação, que uma condição só se aplica ao seu model e
  que cada tipo expõe apenas operadores válidos: igualdade, intervalos, padrões
  de texto literais, `in`/`notIn` e predicados de `NULL`. Inclui composição
  `AND`/`OR`/`NOT`, ordenação, projecções, paginação por offset e cursor keyset
  determinístico com token opaco.
- Modelo estrutural de queries em `tuprel-sql` (`SqlQuery`, `SqlCondition`,
  `SqlOrder`), `INSERT`/`UPDATE ... RETURNING`, e renderização PostgreSQL com
  todos os valores em binds, incluindo `LIMIT` e `OFFSET`.

### Alterado

- Documentação de arquitectura, fases e handoff reconciliada com o fecho da
  Fase 0: zero módulos de produto, primeiro módulo na Fase 1 e RFCs aplicados
  como gates das fases que afectam.
- Os acessores gerados em `P.where` devolvem colunas tipadas e operacionais em
  vez de `TuprelField`. Os ficheiros `where`, `order`, `client` e
  `TuprelClient` compilam contra `tuprel-runtime`. Valores, inputs e metadata
  gerados continuam JDK-only.
- Todos os tipos gerados de topo têm `@javax.annotation.processing.Generated`.
  Assim, análise estática configurada para ignorar código gerado, como a opção
  `disableWarningsInGeneratedCode` do Error Prone, deixa de falhar em builds
  consumidoras com `-Werror`.
- O gerador rejeita nomes de model ou de campo acima de 63 caracteres
  (`TUPREL-CODEGEN-014`), porque passam a ser identificadores PostgreSQL.
- `RenderedSql.toString()` mostra apenas o número de binds, para que registar
  um preview não revele valores de parâmetros.

### Notas

- **Tuprel** é o nome escolhido. Uma pesquisa preliminar de disponibilidade
  não encontrou conflito exacto bloqueante na mesma categoria; não constitui
  clearance profissional de marca. Desenvolvimento público permitido,
  publicação de artefactos ainda desactivada (ver
  `docs/product/NAMING_AND_LEGAL.md`).
- `dev.tuprel` é uma coordenada provisória e não uma coordenada de publicação
  aprovada.
- O cliente gerado ainda não carrega relações, não gere transacções nem cria
  tabelas. As tabelas usam os nomes do schema até existirem `@map`/`@@map` e
  migrations. O roadmap por fases está em `plans/MASTER_PLAN.md`.
