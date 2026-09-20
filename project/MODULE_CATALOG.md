# Module Catalog

| Módulo | Papel | Fase principal |
|---|---|---|
| `tuprel-schema` | parser, AST, validator, diagnostics | 1 |
| `tuprel-codegen-java` | geração Java | 2 |
| `tuprel-sql` | query/SQL AST e binds | 3-4 |
| `tuprel-runtime` | execução, mapping, transactions | 3-5 |
| `tuprel-postgresql` | dialect e tipos PostgreSQL | 3+ |
| `tuprel-migrate` | diff/history/apply/safety | 6 |
| `tuprel-introspection-postgresql` | db pull/catalogs | 8 |
| `tuprel-cli` | comandos Tuprel | 1+ incremental |
| `tuprel-spring-boot-starter` | Spring Boot | 7 |
| `tuprel-gradle-plugin` | integração build Gradle | 7 |
| `tuprel-maven-plugin` | integração build Maven | antes de 1.0 |
| `tuprel-testkit` | contract suites/fixtures | transversal |
| `tuprel-bom` | alinhamento de artefactos | release |
