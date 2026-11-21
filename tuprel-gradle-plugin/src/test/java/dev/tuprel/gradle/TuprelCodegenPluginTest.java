package dev.tuprel.gradle;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarOutputStream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TuprelCodegenPluginTest {
    private static final String SCHEMA = """
            generator java {
              package = "consumer.generated"
            }

            model Product {
              id Int @id
              name String
            }
            """;

    @Test void helpDoesNotResolveAnUnavailableToolchain(@TempDir Path project) throws Exception {
        String repository = System.getProperty("tuprel.fixture.plugin.repository");
        String settings = repository == null ? "" : """
                pluginManagement { repositories { maven { url = uri('%s') } } }
                """.formatted(quote(repository));
        String plugin = repository == null ? "id 'dev.tuprel.codegen'" : "id 'dev.tuprel.codegen' version '0.0.0-fixture'";
        Files.writeString(project.resolve("settings.gradle"), settings + "rootProject.name = 'lazy-consumer'\n");
        writeVerification(project, repository);
        Files.writeString(project.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    %s
                }
                java { toolchain { languageVersion = JavaLanguageVersion.of(99) } }
                tuprelCodegen {
                    cliClasspath.from(fileTree('%s') { include '*.jar' })
                }
                """.formatted(plugin, quote(System.getProperty("tuprel.fixture.artifact.0"))));
        Files.createDirectory(project.resolve("tuprel"));
        Files.writeString(project.resolve("tuprel/schema.tuprel"), SCHEMA);
        BuildResult help = runner(project, "help").build();
        assertOutcome(help, ":help", SUCCESS);
        assertTrue(help.getTasks().stream().noneMatch(task -> task.getPath().startsWith(":tuprel")), help::getOutput);
        BuildResult unavailable = runner(project, "tuprelValidate").buildAndFail();
        assertTrue(unavailable.getOutput().contains("99"), unavailable::getOutput);
        assertTrue(!unavailable.getOutput().contains("Schema is valid"), unavailable::getOutput);
    }

    @Test void compilesGeneratedJavaAndPreservesUserFiles(@TempDir Path project) throws Exception {
        Path cli = Files.createDirectory(project.resolve("installed CLI"));
        try (java.util.stream.Stream<Path> jars = Files.list(Path.of(System.getProperty("tuprel.fixture.artifact.0")))) {
            for (Path jar : jars.filter(path -> path.toString().endsWith(".jar")).toList()) {
                Files.copy(jar, cli.resolve(jar.getFileName()));
            }
        }
        String runtime = Path.of(System.getProperty("tuprel.fixture.artifact.1"), "tuprel-runtime.jar").toString();
        String sql = Path.of(System.getProperty("tuprel.fixture.artifact.2"), "tuprel-sql.jar").toString();
        String repository = System.getProperty("tuprel.fixture.plugin.repository");
        String settings = repository == null ? "" : """
                pluginManagement {
                    repositories {
                        maven { url = uri('%s') }
                    }
                }
                """.formatted(quote(repository));
        Files.writeString(project.resolve("settings.gradle"), settings + """
                rootProject.name = 'external-codegen-consumer'
                buildCache { local { directory = file('isolated-build-cache') } }
                """);
        writeVerification(project, repository);
        String plugin = repository == null ? "id 'dev.tuprel.codegen'"
                : "id 'dev.tuprel.codegen' version '0.0.0-fixture'";
        Files.writeString(project.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    %s
                }
                tuprelCodegen {
                    cliClasspath.from(fileTree('%s') { include '*.jar' })
                }
                dependencies {
                    implementation(files('%s'))
                    implementation(files('%s'))
                }
                """.formatted(plugin, quote(cli.toString()), quote(runtime), quote(sql)));
        Path schema = Files.createDirectories(project.resolve("tuprel")).resolve("schema.tuprel");
        Files.writeString(schema, SCHEMA);
        Path handwritten = Files.createDirectories(project.resolve("src/main/java")).resolve("Consumer.java");
        String application = "class Consumer { consumer.generated.TuprelClient client; }\n";
        Files.writeString(handwritten, application);

        BuildResult initial = runner(project, "tuprelValidate", "compileJava").build();
        assertOutcome(initial, ":tuprelValidate", SUCCESS);
        assertOutcome(initial, ":tuprelGenerate", SUCCESS);
        assertOutcome(initial, ":compileJava", SUCCESS);
        assertOutcome(runner(project, "compileJava").build(), ":tuprelGenerate", UP_TO_DATE);
        Path generated = project.resolve("build/generated/sources/tuprel/main/consumer/generated");
        assertTrue(Files.exists(generated.resolve("TuprelClient.java")));
        runner(project, "clean").build();
        // A global build cache must not restore outputs past the CLI's ownership checks.
        assertOutcome(runner(project, "compileJava").build(), ":tuprelGenerate", SUCCESS);

        Files.writeString(schema, SCHEMA.replace("  name String", "  name String\n  note String?"));
        assertOutcome(runner(project, "compileJava").build(), ":tuprelGenerate", SUCCESS);
        assertEquals(application, Files.readString(handwritten));

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(cli.resolve("extra.jar")))) {
            // An empty fixture JAR changes the input without changing the CLI.
            jar.finish();
        }
        assertOutcome(runner(project, "compileJava").build(), ":tuprelGenerate", SUCCESS);
        BuildResult cachedConfiguration = runner(project, "compileJava").build();
        assertOutcome(cachedConfiguration, ":tuprelGenerate", UP_TO_DATE);
        assertTrue(cachedConfiguration.getOutput().contains("Reusing configuration cache"), cachedConfiguration::getOutput);

        Path manifest = project.resolve("build/generated/sources/tuprel/main/.tuprel-generated");
        String ownership = Files.readString(manifest);
        Path unowned = generated.resolve("TuprelClient.java");
        String original = Files.readString(unowned);
        Files.delete(manifest);
        runner(project, "tuprelGenerate").buildAndFail();
        assertEquals(original, Files.readString(unowned));
        Files.writeString(manifest, ownership);

        Path owned = generated.resolve("TuprelClient.java");
        String manualEdit = Files.readString(owned) + "\n// local edit\n";
        Files.writeString(owned, manualEdit);
        runner(project, "tuprelGenerate").buildAndFail();
        assertEquals(manualEdit, Files.readString(owned));
        assertEquals(application, Files.readString(handwritten));
    }

    private static void assertOutcome(BuildResult result, String taskName, TaskOutcome expected) {
        BuildTask task = result.task(taskName);
        assertNotNull(task, result::getOutput);
        assertEquals(expected, task.getOutcome(), result::getOutput);
    }

    private static GradleRunner runner(Path directory, String... tasks) {
        List<String> arguments = new ArrayList<>(List.of("--build-cache", "--configuration-cache",
                "--configuration-cache-problems=fail", "--dependency-verification", "strict"));
        String repository = System.getProperty("tuprel.fixture.plugin.repository");
        if (repository != null) arguments.add("--offline");
        arguments.addAll(List.of(tasks));
        GradleRunner runner = GradleRunner.create().withProjectDir(directory.toFile()).withArguments(arguments);
        if (repository == null) runner.withPluginClasspath();
        String installation = System.getProperty("tuprel.fixture.gradle.installation");
        if (installation != null) {
            runner.withGradleInstallation(Path.of(installation).toFile());
        }
        return runner;
    }

    private static String quote(String path) {
        return path.replace('\\', '/').replace("'", "\\'");
    }

    private static void writeVerification(Path project, String repository) throws Exception {
        if (repository == null) { return; }
        StringBuilder xml = new StringBuilder("<verification-metadata><configuration>"
                + "<verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures>"
                + "</configuration><components>");
        for (String[] component : List.of(
                new String[]{"dev.tuprel.staging", "tuprel-gradle-plugin"},
                new String[]{"dev.tuprel.codegen", "dev.tuprel.codegen.gradle.plugin"})) {
            xml.append("<component group='").append(component[0]).append("' name='")
                    .append(component[1]).append("' version='0.0.0-fixture'>");
            for (String extension : List.of("pom", "module", "jar")) {
                String name = component[1] + "-0.0.0-fixture." + extension;
                Path file = Path.of(repository, component[0].replace('.', '/'), component[1], "0.0.0-fixture", name);
                if (Files.isRegularFile(file)) {
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                    xml.append("<artifact name='").append(name).append("'><sha256 value='")
                            .append(hash).append("'/></artifact>");
                }
            }
            xml.append("</component>");
        }
        xml.append("</components></verification-metadata>");
        Files.createDirectories(project.resolve("gradle"));
        Files.writeString(project.resolve("gradle/verification-metadata.xml"), xml);
    }
}
