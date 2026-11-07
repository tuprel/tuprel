package fixture.consumer;

import dev.example.tuprel.TuprelClient;
import dev.tuprel.postgresql.PostgresqlDatabase;
import dev.tuprel.runtime.TuprelDatabase;
import java.lang.reflect.Proxy;
import java.sql.Driver;
import java.util.Objects;
import java.util.ServiceLoader;
import javax.sql.DataSource;

public final class Consumer {
    private Consumer() {}

    public static void main(String[] args) {
        DataSource source = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> { throw new AssertionError("Unexpected JDBC call"); });
        TuprelDatabase database = PostgresqlDatabase.using(source);
        TuprelClient client = new TuprelClient(database);
        Objects.requireNonNull(client.user(), "Generated user client");
        // Load the generated API and its transitive runtime without connecting to a database.
        if (ServiceLoader.load(Driver.class).stream().noneMatch(driver -> driver.type().getName().equals("org.postgresql.Driver"))) {
            throw new IllegalStateException("Staged runtime or PostgreSQL driver is missing");
        }
        System.out.println("Generated client and transitive PostgreSQL runtime verified");
    }
}
