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

### Denial of service

Schema/query patológico produz parser blow-up, query sem limite, allocation excessiva ou stream não fechado.

Mitigação: limites razoáveis, algoritmos previsíveis, lifecycle explícito, timeouts e benchmarks.

## Fora do âmbito inicial

O Tuprel não é um sistema de autorização de negócio. Não decide se um utilizador pode ver uma linha; fornece primitivas seguras para a aplicação implementar as suas políticas.
