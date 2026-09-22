package dev.tuprel.codegen;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministically ordered Java source paths, relative to one generated output root. */
public record GeneratedJavaSources(Map<String, String> files) {
    public GeneratedJavaSources {
        Objects.requireNonNull(files, "files");
        files.forEach((path, source) -> {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(source, "source");
        });
        files = Collections.unmodifiableMap(new TreeMap<>(files));
    }
}
