package dev.tuprel.sql;

import java.util.Objects;

/** A single physical table or column name, validated before dialect quoting. */
public record SqlIdentifier(String name) {
    public SqlIdentifier {
        Objects.requireNonNull(name, "name");
        if (name.length() > 63 || !name.matches("[A-Za-z_][A-Za-z_0-9]*")) {
            throw new IllegalArgumentException("Invalid SQL identifier");
        }
    }
}
