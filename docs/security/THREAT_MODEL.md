# Threat Model

## Activos a proteger

- integridade e confidencialidade dos dados da aplicação;
- credenciais de base de dados;
- schema e migration history;
- processo de build e artefactos publicados;
- código gerado;
- aplicação consumidora e processo JVM;
- logs, traces e diagnostics.

## Fronteiras de confiança

1. aplicação consumidora -> Tuprel public API;
2. schema/config -> parser/codegen;
3. query builder -> SQL renderer;
4. SQL renderer -> JDBC/PostgreSQL;
5. database metadata -> introspection;
6. CLI -> filesystem/process environment;
7. migration files -> migration runner;
8. dependency repositories -> Gradle/build/release.

## Ameaças prioritárias

### SQL injection

Input externo tenta alterar estrutura SQL em vez de permanecer como valor.

Mitigação: AST estruturado, bind parameters, PreparedStatement, testes adversariais e API unsafe separada.

### Identifier/code generation injection

Nomes vindos do schema ou introspection podem conter caracteres que alterem Java gerado, SQL ou paths.

Mitigação: normalização, validação, escaping por contexto e mapping de nomes físicos/lógicos.

### Data loss em migrations

Drop/rename/retype pode perder dados ou bloquear tabelas críticas.

Mitigação: classificar risco, preview, revisão, flags explícitas, deploy apenas de migrations versionadas, history e backups como responsabilidade operacional documentada.

### Migration race / partial failure

Duas instâncias executam migration simultaneamente ou uma falha deixa estado inconsistente.

Mitigação: lock/advisory lock, history transaccional onde possível, estados de falha explícitos e recovery testado.

### Secret leakage

URLs JDBC, passwords ou bind values entram em logs e exceptions.

Mitigação: redaction central, logging seguro por omissão, testes e separação entre diagnostics e secrets.

### Path traversal

Nome/configuração tenta escrever generated files ou migrations fora do directório esperado.

Mitigação: canonicalização, root confinement, nomes gerados controlados e testes `../`/symlink.

### Supply chain

Dependência ou plugin comprometido entra no build.

Mitigação: versões controladas, dependency locking, dependency verification, dependency review, vulnerability scanning, CodeQL e revisão de novas dependências.

### Tokens de cursor forjados

Um cliente HTTP devolve tokens de paginação por cursor, que podem ser
alterados, truncados ou construídos à mão para injectar SQL, provocar
alocação excessiva, ecoar conteúdo em mensagens de erro ou saltar para outra
tabela ou ordenação.

Mitigação: formato binário versionado com etiquetas de tipo estáveis, limite
de tamanho e de número de valores, UTF-8 estrito, rejeição de bytes
excedentes e `NULL`, e mensagem fixa sem causa original. Um token só é aceite
para a mesma tabela, ordenação e tipos. Os valores descodificados são sempre
binds. O token não é assinado: é uma posição, não uma autorização (RFC-004).

### Esgotamento por locks, transacções e volume

Uma transacção esquecida, um lock mantido ou um resultado enorme podem bloquear
linhas, esgotar connections do pool ou encher a memória.

Mitigação:
- Não há transacção nem lock implícitos: locks fora de uma transacção são
  rejeitados.
- O handle transaccional é confinado ao bloco e à thread, e a connection é
  restaurada e fechada em todos os caminhos.
- Existem prazo de transacção, timeout por query, `NOWAIT` e `SKIP LOCKED`.
- O streaming só existe dentro de transacções e é fechado quando elas
  terminam.
- Batches estão limitados por statement e exigem transacção quando precisam de
  vários statements.
- Relações carregam-se em lotes de chaves, e escritas em massa exigem sempre
  uma condição (RFC-003, RFC-005).

### Denial of service

Schema/query patológico produz parser blow-up, query sem limite, allocation excessiva ou stream não fechado.

Mitigação: limites razoáveis, algoritmos previsíveis, lifecycle explícito, timeouts e benchmarks.

## Fora do âmbito inicial

O Tuprel não é um sistema de autorização de negócio. Não decide se um utilizador pode ver uma linha; fornece primitivas seguras para a aplicação implementar as suas políticas.
