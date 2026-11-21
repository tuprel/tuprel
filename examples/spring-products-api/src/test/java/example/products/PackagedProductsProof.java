package example.products;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.HexFormat;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Verifies the packaged application in a JVM started after Maven exits. */
public final class PackagedProductsProof {
    private static final Path JAR = Path.of("target/spring-products-api-0.0.1-SNAPSHOT.jar");
    private static final String LIB = "BOOT-INF/lib/";
    private static final Pattern PORT = Pattern.compile("Tomcat started on port (\\d+) \\(http\\)");
    private static final Pattern LOCATION = Pattern.compile("/products/([0-9a-f-]{36})");

    private PackagedProductsProof() {}

    public static void main(String[] args) throws Exception {
        Path artifact = args.length == 0 ? JAR : Path.of(args[0]);
        Path scratch = Files.createTempDirectory(Path.of("target"), "packaged-proof-");
        Path jdbcJar = scratch.resolve("postgresql.jar");
        Path log = scratch.resolve("application.log");
        try {
            inspectArtifact(artifact, jdbcJar);
            verifyOverHttp(artifact, jdbcJar, log);
        } finally {
            deleteOwnedFile(log);
            deleteOwnedFile(jdbcJar);
            Files.delete(scratch);
        }
        System.out.println("Packaged products proof passed: java -jar readiness, validation, POST, GET and cleanup");
    }

    private static void deleteOwnedFile(Path file) throws Exception {
        // Windows can release redirected process handles after its exit event is signalled.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (java.nio.file.FileSystemException failure) {
                if (System.nanoTime() >= deadline) throw failure;
                Thread.sleep(25);
            }
        }
    }

    private static void inspectArtifact(Path artifact, Path jdbcJar) throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (java.util.jar.JarEntry entry : jar.stream().toList()) {
                if (entry.getName().endsWith(".class")) {
                    try (InputStream bytes = jar.getInputStream(entry)) { inspectClass(entry.getName(), bytes); }
                } else if (entry.getName().startsWith(LIB) && entry.getName().endsWith(".jar")) {
                    byte[] bytes;
                    try (InputStream input = jar.getInputStream(entry)) { bytes = input.readNBytes(64 * 1024 * 1024 + 1); }
                    require(bytes.length <= 64 * 1024 * 1024, "Packaged library exceeds fixture inspection limit");
                    System.out.println("LIB " + entry.getName() + " SHA-256 "
                            + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    try (ZipInputStream classes = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                        ZipEntry nested;
                        while ((nested = classes.getNextEntry()) != null) {
                            if (nested.getName().endsWith(".class")) inspectClass(nested.getName(), classes);
                            if (nested.getName().equals("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                                    && entry.getName().startsWith(LIB + "tuprel-spring-boot-starter-")) {
                                require(new String(classes.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim()
                                        .equals("dev.tuprel.spring.boot.TuprelAutoConfiguration"), "Incorrect auto-configuration imports");
                            }
                        }
                    }
                }
            }
            for (String module : new String[]{"spring-boot-starter", "runtime", "sql", "postgresql"}) {
                require(jar.getJarEntry(LIB + "tuprel-" + module + "-0.0.0-fixture.jar") != null,
                        "Missing packaged Tuprel module: " + module);
            }
            require(jar.stream().map(entry -> entry.getName().toLowerCase(Locale.ROOT))
                    .noneMatch(name -> name.matches("boot-inf/lib/.*(?:hibernate|jakarta\\.persistence|spring-data-jpa).*")),
                    "Unexpected packaged JPA or Hibernate dependency");
            try (InputStream provider = nestedLibrary(jar, "bval-jsr");
                    ZipInputStream entries = new ZipInputStream(provider)) {
                ZipEntry entry;
                boolean registered = false;
                while ((entry = entries.getNextEntry()) != null) {
                    if (entry.getName().equals("META-INF/services/jakarta.validation.spi.ValidationProvider")) {
                        registered = true;
                        break;
                    }
                }
                require(registered, "Packaged BVal lacks Jakarta Validation provider registration");
            }
            try (InputStream jdbc = nestedLibrary(jar, "postgresql")) {
                Files.copy(jdbc, jdbcJar);
            }
        }
    }

    private static void inspectClass(String name, InputStream bytes) throws Exception {
        Matcher version = Pattern.compile("META-INF/versions/([0-9]+)/.*").matcher(name);
        if (version.matches() && Integer.parseInt(version.group(1)) > 17) return;
        DataInputStream header = new DataInputStream(bytes);
        require(header.readInt() == 0xCAFEBABE, "Invalid classfile");
        header.readUnsignedShort();
        require(header.readUnsignedShort() <= 61, "Packaged Java 17 profile has incompatible bytecode: " + name);
    }

    private static InputStream nestedLibrary(JarFile jar, String artifact) throws Exception {
        List<String> matches = jar.stream().map(entry -> entry.getName())
                .filter(name -> name.matches(LIB + Pattern.quote(artifact) + "-[0-9][^/]*\\.jar")).toList();
        require(matches.size() == 1, "Expected exactly one packaged " + artifact + " library");
        return jar.getInputStream(jar.getJarEntry(matches.get(0)));
    }

    private static void verifyOverHttp(Path artifact, Path jdbcJar, Path log) throws Exception {
        String url = System.getenv("TUPREL_TEST_JDBC_URL");
        require(url != null && url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/tuprel_devtest"),
                "Expected a loopback test database");
        Properties credentials = new Properties();
        credentials.setProperty("user", System.getenv("TUPREL_TEST_DB_USER"));
        credentials.setProperty("password", System.getenv("TUPREL_TEST_DB_PASSWORD"));
        credentials.setProperty("connectTimeout", "5");
        credentials.setProperty("socketTimeout", "10");
        String schema = "tuprel_products_" + UUID.randomUUID().toString().replace("-", "");
        try (URLClassLoader driverLoader = new URLClassLoader(new URL[]{jdbcJar.toUri().toURL()});
                Connection database = ServiceLoader.load(Driver.class, driverLoader).findFirst().orElseThrow()
                        .connect(url, credentials);
                Statement ddl = database.createStatement()) {
            require(database.getMetaData().getDatabaseMajorVersion() == 17, "Expected PostgreSQL 17");
            ddl.execute("CREATE SCHEMA " + schema);
            try {
                ddl.execute("SET search_path TO " + schema);
                ddl.execute(Files.readString(Path.of("sql/products.sql")));
                exerciseJar(artifact, url, credentials, schema, log);
                try (ResultSet rows = ddl.executeQuery("SELECT name FROM \"Product\"")) {
                    require(rows.next() && "Packaged keyboard".equals(rows.getString(1)) && !rows.next(),
                            "Expected one persisted product in the dedicated schema");
                }
            } finally {
                ddl.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        } catch (SQLException failure) {
            String state = failure.getSQLState();
            throw new IllegalStateException("PostgreSQL proof failed. SQLSTATE="
                    + (state != null && state.matches("[A-Z0-9]{5}") ? state : "UNKNOWN"));
        }
    }

    private static void exerciseJar(Path artifact, String url, Properties credentials, String schema, Path log) throws Exception {
        ProcessBuilder command = new ProcessBuilder(
                ProcessHandle.current().info().command().orElseThrow(), "-jar", artifact.toAbsolutePath().toString(),
                "--server.port=0", "--server.address=127.0.0.1")
                .redirectErrorStream(true).redirectOutput(log.toFile());
        Map<String, String> environment = command.environment();
        environment.keySet().removeIf(name -> name.startsWith("TUPREL_TEST_"));
        environment.put("SPRING_DATASOURCE_URL", url + "?currentSchema=" + schema);
        environment.put("SPRING_DATASOURCE_USERNAME", credentials.getProperty("user"));
        environment.put("SPRING_DATASOURCE_PASSWORD", credentials.getProperty("password"));
        Process application = command.start();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        try {
            URI products = awaitReady(application, log, http);
            require(post(http, products, "{}").statusCode() == 400, "Packaged validation must reject an empty product");
            HttpResponse<String> created = post(http, products, """
                    {"name":"Packaged keyboard","price":49.90,"stock":3}
                    """);
            require(created.statusCode() == 201, "Packaged POST must return 201");
            Matcher location = LOCATION.matcher(created.headers().firstValue("Location").orElse(""));
            require(location.matches(), "Packaged POST must return a product Location");
            HttpResponse<String> found = get(http, products.resolve(location.group()));
            require(found.statusCode() == 200 && found.body().contains(location.group(1))
                    && found.body().contains("Packaged keyboard"), "Packaged GET must return the created product");
        } finally {
            application.destroy();
            if (!application.waitFor(10, TimeUnit.SECONDS)) {
                application.destroyForcibly();
                require(application.waitFor(10, TimeUnit.SECONDS), "Could not stop the packaged application");
            }
            application.getOutputStream().close();
            application.getInputStream().close();
            application.getErrorStream().close();
        }
    }

    private static URI awaitReady(Process application, Path log, HttpClient http) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (application.isAlive() && System.nanoTime() < deadline) {
            Matcher port = PORT.matcher(Files.readString(log));
            if (port.find()) {
                URI products = URI.create("http://127.0.0.1:" + port.group(1) + "/products");
                require(get(http, products).statusCode() == 200, "Packaged readiness query failed");
                return products;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Packaged application exited or failed to become ready within 45 seconds");
    }

    private static HttpResponse<String> get(HttpClient http, URI uri) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient http, URI uri, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
