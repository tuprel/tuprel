# Offline logical migration diff

This Java 21 example compares two completely known logical states using the
accepted PostgreSQL DDL AST. It prints a proposal; it never opens a connection,
writes migrations or executes SQL. This is a Java API example, not a CLI command.

From the repository root, PowerShell:

```powershell
.\gradlew.bat :tuprel-migrate-postgresql:classes --dependency-verification strict
$diffClasspath = 'tuprel-migrate-postgresql/build/classes/java/main;tuprel-migrate/build/classes/java/main;tuprel-sql/build/classes/java/main'
javac --release 21 -cp $diffClasspath -d build/migration-diff-example examples/migration-diff/LogicalDiffExample.java
java -cp "build/migration-diff-example;$diffClasspath" LogicalDiffExample
```

Bash:

```bash
./gradlew :tuprel-migrate-postgresql:classes --dependency-verification strict
diff_classpath='tuprel-migrate-postgresql/build/classes/java/main:tuprel-migrate/build/classes/java/main:tuprel-sql/build/classes/java/main'
javac --release 21 -cp "$diff_classpath" -d build/migration-diff-example examples/migration-diff/LogicalDiffExample.java
java -cp "build/migration-diff-example:$diff_classpath" LogicalDiffExample
```

The result requires review and retains PHYSICAL_STATE_NOT_CHECKED and the
ACCESS_EXCLUSIVE_LOCK warning for ADD COLUMN. Removal/type/constraint/index
changes block the complete SQL preview. Unknown SQL refuses the input state.
Column declaration order is excluded from logical equality; index key order
is significant. This does not read schema.tuprel or inspect a live database.

See the [migration guide](../../docs/guides/migrations.md#compare-logical-schemas-offline)
and [accepted contract](../../docs/adr/ADR-0018-logical-schema-diff.md).
