package dev.tuprel.gradle;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    @Test void compilesGeneratedJavaAndPreservesUserFiles(@TempDir Path project) throws Exception {
        String cli = System.getProperty("tuprel.fixture.artifact.0");
        String runtime = System.getProperty("tuprel.fixture.artifact.1");
        String sql = System.getProperty("tuprel.fixture.artifact.2");
        String repository = System.getProperty("tuprel.fixture.plugin.repository");
        String settings = repository == null ? "" : """
                pluginManagement {
                    repositories {
                        maven { url = uri('%s') }
                    }
                }
                """.formatted(quote(repository));
        Files.writeString(project.resolve("settings.gradle"), settings + "rootProject.name = 'external-codegen-consumer'\n");
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
                    implementation(fileTree('%s') { include '*.jar' })
                    implementation(fileTree('%s') { include '*.jar' })
                }
                """.formatted(plugin, quote(cli), quote(runtime), quote(sql)));
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
        List<String> arguments = new ArrayList<>(List.of("--build-cache"));
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
}
