# ADR-0013 — Adapter PostgreSQL de migrations separado do driver de runtime

Estado: **proposto; aprovação obrigatória antes de F6-002**. Sem código JDBC
ou novo módulo adapter em F6-001. Data: 2026-10-30.

## Contexto

`tuprel-postgresql` é consumido por aplicações CRUD/query e já depende de
runtime/SQL. Acrescentar migrate como dependência obrigatória faria chegar
tooling de filesystem e deployment a todas essas aplicações. O runtime
transaccional existente controla operações de queries; não é um executor de DDL
arbitrário e não deve receber uma escape hatch apenas para migrations.

## Decisão recomendada

Criar **`tuprel-migrate-postgresql` em F6-002**, quando houver reader real de
histórico, não preventivamente nesta fatia. Depender de `tuprel-migrate` para
ports e modelos puros e de `tuprel-sql` para identificadores validados existentes.
JDBC/DataSource fica no adapter; driver PG é dependência de runtime do
consumidor que o compõe. Não há dependência nova no driver CRUD.

```text
Agora:       cli -> schema, codegen, migrate
             codegen -> schema
             migrate -> java.base
F6-002+:     cli -> migrate-postgresql -> migrate, sql
F6-004+:     migrate -> schema (modelo validado, sem I/O)
Sem aresta:  postgresql -> migrate; migrate -> qualquer adapter/CLI
```

Criar ports só quando usados: reader de histórico em F6-002; executor/lock em
F6-003; leitor de catálogo parcial em F6-005. Não criar interfaces vazias agora.
No adapter reutilizar `SqlIdentifier` e PreparedStatement/binding para valores;
quoting de identificadores é responsabilidade explícita do dialecto. Não
duplicar schema compiler/validator nem o parser de configuração do schema.

## Configuração proposta para os comandos DB

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

## Gate de aprovação

Confirmar módulo separado, configuração de produção e ports públicos concretos
no plano de F6-002. Só então criar adapter, SELECTs de histórico e testes de DB.
Não acrescentar a aresta futura ou uma dependência de driver antes de existir
código real que a use.
