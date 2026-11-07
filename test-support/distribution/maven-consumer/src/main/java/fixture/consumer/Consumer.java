package fixture.consumer;

import dev.example.tuprel.TuprelClient;
import dev.tuprel.postgresql.PostgresqlDatabase;
import dev.tuprel.runtime.TuprelDatabase;
import java.sql.Driver;
import java.util.ServiceLoader;
import javax.sql.DataSource;

public final class Consumer {
    private Consumer() {}

    public static void main(String[] args) throws ReflectiveOperationException {
        // Load the generated API and its transitive runtime without connecting to a database.
        if (TuprelClient.class.getConstructors().length == 0
                || PostgresqlDatabase.class.getMethod("using", DataSource.class).getReturnType() != TuprelDatabase.class
                || ServiceLoader.load(Driver.class).stream().noneMatch(driver -> driver.type().getName().equals("org.postgresql.Driver"))) {
            throw new IllegalStateException("Staged runtime or PostgreSQL driver is missing");
        }
        System.out.println("Generated client and transitive PostgreSQL runtime verified");
    }
}
