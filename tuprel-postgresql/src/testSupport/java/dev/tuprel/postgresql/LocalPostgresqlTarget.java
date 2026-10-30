package dev.tuprel.postgresql;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/** Fail-closed target validation shared by local integration tests and their unit tests. */
final class LocalPostgresqlTarget {
    private LocalPostgresqlTarget() { }

    static void validate(String jdbcUrl) {
        String message = "Use a local JDBC URL with an explicit port and database tuprel_devtest, "
                + "without credentials or query parameters";
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException(message);
        }
        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            if (uri.getHost() == null || !Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                    || uri.getPort() < 1 || uri.getPort() > 65535
                    || !"/tuprel_devtest".equals(uri.getRawPath()) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(message);
            }
        } catch (URISyntaxException exception) {
            // Do not attach the parsing exception: it contains the supplied URL.
            throw new IllegalArgumentException(message);
        }
    }
}
