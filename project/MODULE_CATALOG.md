# Module Catalog

| Módulo | Papel | Fase principal |
|---|---|---|
| `tuprel-schema` | lexer, parser, AST, validator, diagnostics, formatter, emparelhamento de relações | 1, 5 (implementado) |
| `tuprel-codegen-java` | geração Java a partir do schema validado, incluindo cliente operacional, relações e transacções | 2, 4-5 (implementado) |
| `tuprel-sql` | CRUD estrutural, queries, locks, batch e binds | 3-5 (subconjunto implementado) |
| `tuprel-runtime` | DataSource/JDBC, mapping, erros, query API tipada, relações, transacções e streaming | 3-5 (subconjunto implementado) |
| `tuprel-postgresql` | renderer de CRUD, queries, locks e batch, binding PostgreSQL, suite de integração real; tipos avançados mais tarde | 3-5 (subconjunto implementado), 9 |
| `tuprel-migrate` | diff/history/apply/safety | 6 |
| `tuprel-introspection-postgresql` | db pull/catalogs | 8 |
| `tuprel-cli` | `validate`, `format` e `generate` iniciais | 1-2 (incremental) |
| `tuprel-spring-boot-starter` | Spring Boot | 7 |
| `tuprel-gradle-plugin` | integração build Gradle | 7 |
| `tuprel-maven-plugin` | integração build Maven | antes de 1.0 |
| `tuprel-testkit` | contract suites/fixtures | transversal |
| `tuprel-bom` | alinhamento de artefactos | release |
