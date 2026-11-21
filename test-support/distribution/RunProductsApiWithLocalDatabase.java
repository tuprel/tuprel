import dev.tuprel.testsupport.LocalPostgresqlTarget;
import dev.tuprel.testsupport.LocalTestEnvironment;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/** Runs the external HTTP proof using the existing local test settings. */
public final class RunProductsApiWithLocalDatabase {
    private RunProductsApiWithLocalDatabase() {}

    public static void main(String[] args) throws Exception {
        LocalTestEnvironment settings = LocalTestEnvironment.load(Path.of(args[0]));
        String url = settings.required("TUPREL_TEST_JDBC_URL");
        LocalPostgresqlTarget.validate(url);
        ProcessBuilder verification = new ProcessBuilder(Arrays.copyOfRange(args, 2, args.length))
                .directory(Path.of(args[1]).toFile()).inheritIO();
        Map<String, String> environment = verification.environment();
        environment.put("TUPREL_TEST_JDBC_URL", url);
        environment.put("TUPREL_TEST_DB_USER", settings.required("TUPREL_TEST_DB_USER"));
        environment.put("TUPREL_TEST_DB_PASSWORD", settings.required("TUPREL_TEST_DB_PASSWORD"));
        System.exit(verification.start().waitFor());
    }
}
