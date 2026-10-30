package dev.tuprel.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Local configuration for examples and tests only; never part of the Tuprel runtime. */
public final class LocalTestEnvironment {
    private static final Set<String> NAMES = Set.of(
            "TUPREL_TEST_JDBC_URL", "TUPREL_TEST_DB_USER", "TUPREL_TEST_DB_PASSWORD");
    private final Map<String, String> values;

    private LocalTestEnvironment(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /**
     * Loads an optional UTF-8 .env at exactly the supplied path. Nonblank process environment
     * values take precedence. Values are literal: no interpolation, commands or escape evaluation.
     * No configuration values or original parsing errors appear in diagnostics.
     */
    public static LocalTestEnvironment load(Path file) {
        return load(file, System.getenv());
    }

    static LocalTestEnvironment load(Path file, Map<String, String> processEnvironment) {
        Map<String, String> values = new HashMap<>();
        if (Files.exists(file)) {
            try {
                if (Files.size(file) > 16_384) {
                    throw new IllegalArgumentException("Local test .env exceeds 16 KiB");
                }
                int lineNumber = 0;
                for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    lineNumber++;
                    String line = (lineNumber == 1 && raw.startsWith("\uFEFF") ? raw.substring(1) : raw).strip();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int separator = line.indexOf('=');
                    if (separator < 1) {
                        throw invalidLine(lineNumber);
                    }
                    String name = line.substring(0, separator).strip();
                    String value = line.substring(separator + 1).strip();
                    if (!NAMES.contains(name) || values.containsKey(name)) {
                        throw invalidLine(lineNumber);
                    }
                    if (value.startsWith("\"") || value.startsWith("'")) {
                        if (value.length() < 2 || value.charAt(value.length() - 1) != value.charAt(0)) {
                            throw invalidLine(lineNumber);
                        }
                        value = value.substring(1, value.length() - 1);
                    }
                    values.put(name, value);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read local test .env");
            }
        }
        for (String name : NAMES) {
            String value = processEnvironment.get(name);
            if (value != null && !value.isBlank()) {
                values.put(name, value);
            }
        }
        return new LocalTestEnvironment(values);
    }

    /** Requires a nonblank allowed test setting, without exposing its value in errors. */
    public String required(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("Unsupported local test setting");
        }
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name + " in the playground .env or process environment");
        }
        return value;
    }

    @Override
    public String toString() {
        return "LocalTestEnvironment[values redacted]";
    }

    private static IllegalArgumentException invalidLine(int lineNumber) {
        return new IllegalArgumentException("Invalid local test .env entry at line " + lineNumber);
    }
}
