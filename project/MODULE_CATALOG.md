# Module Catalog

| Módulo | Papel | Fase principal |
|---|---|---|
| `jorvia-schema` | parser, AST, validator, diagnostics | 1 |
| `jorvia-codegen-java` | geração Java | 2 |
| `jorvia-sql` | query/SQL AST e binds | 3-4 |
| `jorvia-runtime` | execução, mapping, transactions | 3-5 |
| `jorvia-postgresql` | dialect e tipos PostgreSQL | 3+ |
| `jorvia-migrate` | diff/history/apply/safety | 6 |
| `jorvia-introspection-postgresql` | db pull/catalogs | 8 |
| `jorvia-cli` | comandos Jorvia | 1+ incremental |
| `jorvia-spring-boot-starter` | Spring Boot | 7 |
| `jorvia-gradle-plugin` | integração build Gradle | 7 |
| `jorvia-maven-plugin` | integração build Maven | antes de 1.0 |
| `jorvia-testkit` | contract suites/fixtures | transversal |
| `jorvia-bom` | alinhamento de artefactos | release |
