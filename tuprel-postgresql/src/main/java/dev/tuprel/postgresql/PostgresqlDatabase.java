package dev.tuprel.postgresql;

import dev.tuprel.runtime.TuprelDatabase;
import javax.sql.DataSource;

/** Explicit PostgreSQL entry point; the caller owns the DataSource. */
public final class PostgresqlDatabase {
    private PostgresqlDatabase() { }

    /** Creates a reusable runtime using the supplied PostgreSQL DataSource. */
    public static TuprelDatabase using(DataSource dataSource) {
        return new TuprelDatabase(dataSource, new PostgresqlRenderer(), new PostgresqlBinder());
    }
}
