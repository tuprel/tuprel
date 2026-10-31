# ADR-0013 — Adapter PostgreSQL de migrations separado do driver de runtime

Estado: **aceite** em 2026-10-30 por aprovação explícita da separação do adapter.
F6-002B implementa apenas leitura; não autoriza execução de migrations.

## Contexto

`tuprel-postgresql` é consumido por aplicações CRUD/query e já depende de
runtime/SQL. Acrescentar migrate como dependência obrigatória faria chegar
tooling de filesystem e deployment a todas essas aplicações. O runtime
transaccional existente controla operações de queries; não é um executor de DDL
arbitrário e não deve receber uma escape hatch apenas para migrations.

## Decisão

Criar **`tuprel-migrate-postgresql` em F6-002B**, quando houver reader real de
histórico, não preventivamente nesta fatia. Depender de `tuprel-migrate` para
ports e modelos puros e de `tuprel-sql` para identificadores validados existentes.
JDBC/DataSource fica no adapter; driver PG é dependência de runtime do
consumidor que o compõe. Não há dependência nova no driver CRUD.

```text
Agora:       cli -> schema, codegen, migrate
             codegen -> schema
             migrate -> java.base
F6-002B+:    cli -> migrate-postgresql -> migrate, sql
F6-004B+:    migrate-postgresql -> schema (ADR-0019 aceite; projecção física)
Sem aresta:  postgresql -> migrate; migrate -> qualquer adapter/CLI
```

Criar ports só quando usados: reader de histórico em F6-002B; executor/lock em
F6-003; leitor de catálogo parcial em F6-005. Não criar interfaces vazias agora.
No adapter reutilizar `SqlIdentifier` e PreparedStatement/binding para valores;
quoting de identificadores é responsabilidade explícita do dialecto. Não
duplicar schema compiler/validator nem o parser de configuração do schema.

## Configuração dos comandos DB

Configuração de produção separada do suporte de testes: variáveis
`TUPREL_DATABASE_URL`, `TUPREL_DATABASE_USER`, `TUPREL_DATABASE_PASSWORD` e
schema alvo explícito. Sem loader `.env` automático, inferência por nome de DB,
password na linha de comandos ou configuração de segredos no Gradle. A API
embebida recebe DataSource no adapter; domínio puro não expõe tipos JDBC.
TLS, timeouts e redacção são validados antes da primeira ligação. A futura
configuração por datasource do schema deve reutilizar o AST existente, com ADR
de precedência antes de acrescentar outra origem/config file.

Para testes mantêm-se as variáveis `TUPREL_TEST_*` e o `.env` ignorado do
playground, apenas em fixtures/exemplos. Testes locais usam PostgreSQL 17 real,
schemas aleatórios, nunca Docker; CI conserva a opção container explícita.

## Alternativas

- Adapter no `tuprel-postgresql`: menos um módulo, mas contamina o grafo de
  dependências de aplicações que não usam migrations. Rejeitado para este desenho.
- Adapter JDBC dentro de migrate: menos composição, mas perde o boundary puro,
  torna testes e suporte de outros dialectos mais difíceis. Rejeitado.
- Reutilizar `TuprelDatabase.transaction`: lifecycle útil como referência, mas o
  lock de sessão precisa de sobreviver aos commits entre migrations. Não impor
  uma API de queries ao executor de infraestrutura.

## Limite da implementação actual

Módulo separado, configuração de produção e port MigrationHistoryReader
implementados na F6-002B, com SELECTs de histórico e testes reais. A CLI compõe
o driver PG 42.7.14 já usado no projecto; o adapter recebe DataSource e não
obriga consumidores a essa implementação. Executor e locks continuam fora.

## Revisão da aresta futura — ADR-0019

A aprovação da F6-004B mantém migrate puro (java.base). A projecção física
consome ValidatedSchema no adapter PostgreSQL, com api(schema) por exposição
real nas assinaturas públicas. Sem alteração de runtime CRUD ou ciclos.
