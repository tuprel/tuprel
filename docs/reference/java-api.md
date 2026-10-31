[Documentation](../README.md) / Reference

# Java API reference

JavaDoc is generated from the source you build. There is no hosted API site or
stable published artifact yet.

## Generate JavaDoc

From the checkout root:

```bash
./gradlew javadoc --dependency-verification strict
```

PowerShell: `.\gradlew.bat javadoc --dependency-verification strict`.
No database is needed. Open per-module HTML locally, for example
tuprel-runtime/build/docs/javadoc/index.html or
tuprel-postgresql/build/docs/javadoc/index.html. The root has no aggregate page;
these ignored generated paths are intentionally not GitHub links.

## Entry points

| API | Source |
|---|---|
| DataSource adapter | [PostgresqlDatabase](../../tuprel-postgresql/src/main/java/dev/tuprel/postgresql/PostgresqlDatabase.java) |
| Transactions/database | [TuprelDatabase](../../tuprel-runtime/src/main/java/dev/tuprel/runtime/TuprelDatabase.java) |
| Transaction settings | [TransactionOptions](../../tuprel-runtime/src/main/java/dev/tuprel/runtime/TransactionOptions.java) |
| JDBC failures | [TuprelDatabaseException](../../tuprel-runtime/src/main/java/dev/tuprel/runtime/TuprelDatabaseException.java) |
| Typed query API | [Query package](../../tuprel-runtime/src/main/java/dev/tuprel/runtime/query/) |
| SQL preview | [RenderedSql](../../tuprel-sql/src/main/java/dev/tuprel/sql/RenderedSql.java) |
| Schema frontend | [SchemaCompiler](../../tuprel-schema/src/main/java/dev/tuprel/schema/SchemaCompiler.java) |
| Java generator | [JavaCodeGenerator](../../tuprel-codegen-java/src/main/java/dev/tuprel/codegen/JavaCodeGenerator.java) |
| Offline catalog | [MigrationDirectory](../../tuprel-migrate/src/main/java/dev/tuprel/migrate/MigrationDirectory.java) |

## Generated APIs belong to your schema

`TuprelClient`, ProductClient, ProductCreate, ProductUpdate and selectors are
generated into your configured package, not provided by a published client JAR.

Compile the [playground](../../examples/tuprel-playground/README.md) to inspect
real generated sources under its build/generated/sources/tuprel/main. Do not
link to ignored files as committed GitHub documentation. Working fragments and
imports are in the [client guide](../guides/java-client.md).
