import dev.tuprel.migrate.postgresql.diff.PostgresqlSchemaProjector;
import dev.tuprel.migrate.postgresql.diff.SchemaDiff;
import dev.tuprel.migrate.postgresql.diff.SchemaProjection;
import dev.tuprel.migrate.postgresql.diff.SchemaSnapshot;
import dev.tuprel.schema.SchemaCompiler;
import dev.tuprel.schema.SourceText;
import dev.tuprel.schema.model.ValidatedSchema;
import dev.tuprel.sql.SqlIdentifier;
import java.util.List;

/** Projects a complete supported schema without a database. */
public final class SchemaProjectionExample {
    private SchemaProjectionExample() { }

    /** Prints canonical proposal SQL, never execution approval. */
    public static void main(String[] arguments) {
        SqlIdentifier target = new SqlIdentifier("app");
        ValidatedSchema model = new SchemaCompiler().compile(SourceText.of("schema.tuprel",
                "model Product { id UUID @id label String? @@index([label]) }")).validatedSchema().orElseThrow();
        SchemaProjection projection = new PostgresqlSchemaProjector().project(target, model);
        SchemaDiff diff = SchemaDiff.compare(SchemaSnapshot.replay(target, List.of()), projection.snapshot().orElseThrow());
        System.out.println(diff.state());
        System.out.print(diff.preview());
    }
}
