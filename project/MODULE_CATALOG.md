# Module Catalog

| Módulo | Papel | Fase principal |
|---|---|---|
| `tuprel-schema` | lexer, parser, AST, validator, diagnostics, formatter | 1 (implementado) |
| `tuprel-codegen-java` | geração Java a partir do schema validado | 2 (implementado) |
| `tuprel-sql` | CRUD estrutural e binds; query AST completo mais tarde | 3 (subconjunto implementado), 4 |
| `tuprel-runtime` | DataSource/JDBC, mapping e erros; transactions mais tarde | 3 (subconjunto implementado), 5 |
| `tuprel-postgresql` | renderer e binding PostgreSQL, suite de integração real; tipos avançados mais tarde | 3 (subconjunto implementado), 9 |
| `tuprel-migrate` | diff/history/apply/safety | 6 |
| `tuprel-introspection-postgresql` | db pull/catalogs | 8 |
| `tuprel-cli` | `validate`, `format` e `generate` iniciais | 1-2 (incremental) |
| `tuprel-spring-boot-starter` | Spring Boot | 7 |
| `tuprel-gradle-plugin` | integração build Gradle | 7 |
| `tuprel-maven-plugin` | integração build Maven | antes de 1.0 |
| `tuprel-testkit` | contract suites/fixtures | transversal |
| `tuprel-bom` | alinhamento de artefactos | release |
