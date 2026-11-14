package example.products;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ProductsHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEYBOARD = """
            {"name":"Keyboard","price":49.90,"stock":3}
            """;
    private static final String MOUSE = """
            {"name":"Mouse","price":19.90,"stock":0}
            """;

    @Test
    void servesProductCrudOverHttpAgainstPostgresql17() throws Exception {
        String jdbc = setting("TUPREL_TEST_JDBC_URL");
        if (!jdbc.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/tuprel_devtest")) {
            throw new IllegalStateException("Use the loopback tuprel_devtest database without JDBC options.");
        }
        String user = setting("TUPREL_TEST_DB_USER");
        String password = setting("TUPREL_TEST_DB_PASSWORD");
        String schema = "tuprel_products_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection database = DriverManager.getConnection(jdbc, user, password);
                Statement ddl = database.createStatement()) {
            assertEquals(17, database.getMetaData().getDatabaseMajorVersion());
            ddl.execute("CREATE SCHEMA " + schema);
            try {
                ddl.execute("SET search_path TO " + schema);
                ddl.execute(Files.readString(Path.of("sql/products.sql")));
                withApplication(jdbc, user, password, schema, ddl);
            } finally {
                ddl.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void withApplication(String jdbc, String user, String password, String schema, Statement ddl)
            throws Exception {
        SpringApplication application = new SpringApplication(ProductsApplication.class);
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("isolatedPostgresql", Map.of(
                        "spring.datasource.url", jdbc + "?currentSchema=" + schema,
                        "spring.datasource.username", user, "spring.datasource.password", password,
                        "server.port", "0", "server.address", "127.0.0.1"))));
        try (ConfigurableApplicationContext context = application.run();
                HttpClient http = HttpClient.newHttpClient()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            URI base = URI.create("http://127.0.0.1:" + port);
            HttpResponse<String> first = request(http, base, "POST", "/products", KEYBOARD);
            assertEquals(201, first.statusCode());
            String location = first.headers().firstValue("Location").orElseThrow();
            assertEquals("/products/" + json(first).get("id").stringValue(), location);
            assertEquals("Keyboard", json(request(http, base, "GET", location, null)).get("name").stringValue());
            assertEquals(201, request(http, base, "POST", "/products", MOUSE).statusCode());

            JsonNode page = json(request(http, base, "GET", "/products?size=1", null));
            JsonNode next = json(request(http, base, "GET", "/products?page=1&size=1", null));
            assertEquals(1, page.get("items").size());
            assertEquals(1, next.get("items").size());
            assertTrue(page.get("hasNext").asBoolean());
            assertFalse(next.get("hasNext").asBoolean());
            assertFalse(page.get("items").get(0).get("id").equals(next.get("items").get(0).get("id")));

            for (String invalid : List.of("{}", "{", KEYBOARD.replace("49.90", "49.901"),
                    KEYBOARD.replace("Keyboard", " "), KEYBOARD.replace("\"stock\":3", "\"stock\":-1"),
                    KEYBOARD.replace("\"stock\":3", "\"stock\":1.5"),
                    KEYBOARD.replace("\"stock\":3", "\"stock\":3,\"unknown\":0"))) {
                assertProblem(400, request(http, base, "POST", "/products", invalid));
            }
            assertProblem(400, request(http, base, "PUT", location, "{}"));
            assertProblem(400, request(http, base, "GET", "/products/not-a-uuid", null));
            assertProblem(400, request(http, base, "GET", "/products?page=-1&size=101", null));
            assertProblem(400, request(http, base, "GET", "/products?page=10001", null));

            String literal = "O'Reilly'); DROP TABLE product;--";
            HttpResponse<String> updated = request(http, base, "PUT", location, KEYBOARD.replace("Keyboard", literal));
            assertEquals(200, updated.statusCode());
            assertEquals(literal, json(updated).get("name").stringValue());
            try (ResultSet rows = ddl.executeQuery("SELECT count(*) FROM \"Product\"")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }

            HttpResponse<String> deleted = request(http, base, "DELETE", location, null);
            assertEquals(204, deleted.statusCode());
            assertTrue(deleted.body().isEmpty());
            assertProblem(404, request(http, base, "GET", location, null));
            assertProblem(404, request(http, base, "PUT", location, KEYBOARD));
            assertProblem(404, request(http, base, "DELETE", location, null));

            ddl.execute("ALTER TABLE \"Product\" RENAME TO product_unavailable");
            HttpResponse<String> failure = request(http, base, "GET", "/products", null);
            assertProblem(500, failure);
            assertFalse(failure.body().contains("42P01"));
            assertFalse(failure.body().contains(schema));
            assertFalse(failure.body().contains(jdbc));
        }
    }

    private static HttpResponse<String> request(HttpClient http, URI base, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static void assertProblem(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        assertEquals(status, json(response).get("status").asInt());
    }

    private static String setting(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing test setting " + name);
        }
        return value;
    }
}
