import dev.tuprel.migrate.MigrationFile;
import dev.tuprel.migrate.MigrationId;
import dev.tuprel.migrate.postgresql.ddl.DdlParseResult;
import dev.tuprel.migrate.postgresql.ddl.PostgresqlDdlParser;
import dev.tuprel.migrate.postgresql.diff.SchemaDiff;
import dev.tuprel.migrate.postgresql.diff.SchemaSnapshot;
import dev.tuprel.sql.SqlIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Offline logical comparison only; never connects, creates files or executes SQL. */
public final class LogicalDiffExample {
    private LogicalDiffExample() { }

    /** Prints a proposal for manual review, not a deployment approval. */
    public static void main(String[] arguments) {
        SqlIdentifier schema = new SqlIdentifier("app");
        String initial = "CREATE TABLE \"app\".\"Product\" (\"id\" UUID PRIMARY KEY);\n";
        String desired = initial + "ALTER TABLE \"app\".\"Product\" ADD COLUMN \"label\" TEXT;\n";
        SchemaSnapshot before = snapshot(schema, initial);
        SchemaSnapshot after = snapshot(schema, desired);
        SchemaDiff diff = SchemaDiff.compare(before, after);
        if (diff.state() != SchemaDiff.State.REVIEW_REQUIRED
                || !diff.risks().contains(SchemaDiff.Risk.PHYSICAL_STATE_NOT_CHECKED)) {
            throw new IllegalStateException("Unexpected comparison result");
        }
        System.out.println(diff.state());
        System.out.println(diff.risks());
        System.out.print(diff.preview());
    }

    private static SchemaSnapshot snapshot(SqlIdentifier schema, String sql) {
        MigrationFile file = new MigrationFile(new MigrationId("20261031_120000"), "example", sql.getBytes(StandardCharsets.UTF_8));
        DdlParseResult parsed = new PostgresqlDdlParser().parse(file);
        return SchemaSnapshot.fromParsed(schema, List.of(parsed));
    }
}
