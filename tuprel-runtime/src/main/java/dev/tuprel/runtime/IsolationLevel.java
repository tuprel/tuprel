package dev.tuprel.runtime;

import java.sql.Connection;

/** Transaction isolation levels; their exact guarantees are those of the database. */
public enum IsolationLevel {
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

    private final int jdbcLevel;

    IsolationLevel(int jdbcLevel) {
        this.jdbcLevel = jdbcLevel;
    }

    int jdbcLevel() {
        return jdbcLevel;
    }
}
