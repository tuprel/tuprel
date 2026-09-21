// Nota sobre o nome do package: `buildlogic` e não `build`.
//
// O .gitignore ignora directórios `build` a qualquer nível, para apanhar os
// outputs do Gradle. Um package chamado `build` produziria o caminho de fontes
// src/test/kotlin/dev/tuprel/build/, que essa regra também apanha — o teste
// ficaria invisível para o Git sem qualquer aviso.
package dev.tuprel.buildlogic

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Testes do convention plugin `tuprel.java-conventions`.
 *
 * Cada teste constrói um projecto Java temporário que aplica o plugin e corre
 * um build Gradle real com TestKit. O objectivo é provar comportamento — o que
 * um módulo consumidor recebe de facto — e não a existência de ficheiros.
 *
 * As fixtures Java vivem apenas no directório temporário do teste. Os módulos
 * reais da Fase 1 também aplicam a mesma convenção no build principal.
 */
class JavaConventionsPluginTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    @DisplayName("aplica a baseline Java: toolchain 21, release 21, UTF-8, -Xlint:all, -Werror, Error Prone e arquivos reproduzíveis")
    fun configuresJavaBaseline() {
        writeSettings()
        writeBuild(
            """
            val javaExtension = project.extensions.getByType(JavaPluginExtension::class.java)
            val compileJava = tasks.named("compileJava", JavaCompile::class.java)
            val jarTask = tasks.named("jar", Jar::class.java)
            val sourceSetNames = project.extensions.getByType(SourceSetContainer::class.java).names.sorted()
            val errorProneDependencies = configurations.getByName("errorprone").dependencies.map {
                it.group + ":" + it.name + ":" + it.version
            }

            tasks.register("conventionReport") {
                doLast {
                    println("REPORT toolchain=" + javaExtension.toolchain.languageVersion.get())
                    println("REPORT release=" + compileJava.get().options.release.get())
                    println("REPORT encoding=" + compileJava.get().options.encoding)
                    println("REPORT compilerArgs=" + compileJava.get().options.compilerArgs)
                    println("REPORT preserveFileTimestamps=" + jarTask.get().isPreserveFileTimestamps)
                    println("REPORT reproducibleFileOrder=" + jarTask.get().isReproducibleFileOrder)
                    println("REPORT sourceSets=" + sourceSetNames)
                    println("REPORT errorprone=" + errorProneDependencies)
                }
            }
            """.trimIndent(),
        )

        val result: BuildResult = runner("conventionReport").build()
        val output = result.output

        assertContains(output, "REPORT toolchain=21")
        assertContains(output, "REPORT release=21")
        assertContains(output, "REPORT encoding=UTF-8")
        assertContains(output, "-Xlint:all")
        assertContains(output, "-Werror")
        assertContains(output, "REPORT preserveFileTimestamps=false")
        assertContains(output, "REPORT reproducibleFileOrder=true")
        assertContains(output, "integrationTest")
        assertContains(output, "com.google.errorprone:error_prone_core:2.50.0")
    }

    @Test
    @DisplayName("unit tests correm na JUnit Platform com a baseline JUnit 5")
    fun runsUnitTestsOnJUnitPlatform() {
        writeSettings()
        writeBuild()
        writeCalculator()
        writeSource(
            "src/test/java/fixture/CalculatorTest.java",
            """
            package fixture;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            class CalculatorTest {

                @Test
                void addsTwoNumbers() {
                    assertEquals(3, new Calculator().add(1, 2));
                }
            }
            """.trimIndent(),
        )

        val result: BuildResult = runner("test").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        val report = projectDir.resolve("build/test-results/test/TEST-fixture.CalculatorTest.xml")
        assertTrue(report.isFile, "relatório JUnit Platform em falta: " + report)
        assertContains(report.readText(), "addsTwoNumbers")
    }

    @Test
    @DisplayName("integrationTest existe, corre depois dos unit tests e participa no check")
    fun wiresIntegrationTestIntoCheck() {
        writeSettings()
        writeBuild()
        writeCalculator()
        writeSource(
            "src/test/java/fixture/CalculatorTest.java",
            """
            package fixture;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            class CalculatorTest {

                @Test
                void addsTwoNumbers() {
                    assertEquals(3, new Calculator().add(1, 2));
                }
            }
            """.trimIndent(),
        )
        writeSource(
            "src/integrationTest/java/fixture/CalculatorIntegrationTest.java",
            """
            package fixture;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            class CalculatorIntegrationTest {

                @Test
                void addsLargeNumbers() {
                    assertEquals(300, new Calculator().add(100, 200));
                }
            }
            """.trimIndent(),
        )

        val result: BuildResult = runner("check").build()
        val output = result.output

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":integrationTest")?.outcome)

        // O source set de integration tests vê o output de `main` e herda as
        // dependências de teste: sem isso a fixture nem compilaria.
        val report = projectDir.resolve(
            "build/test-results/integrationTest/TEST-fixture.CalculatorIntegrationTest.xml",
        )
        assertTrue(report.isFile, "relatório de integrationTest em falta: " + report)

        // Ordem: unit tests primeiro.
        val testIndex = output.indexOf("> Task :test")
        val integrationIndex = output.indexOf("> Task :integrationTest")
        assertTrue(testIndex >= 0, "task :test não executou")
        assertTrue(integrationIndex >= 0, "task :integrationTest não executou")
        assertTrue(
            testIndex < integrationIndex,
            ":integrationTest devia correr depois de :test",
        )
    }

    @Test
    @DisplayName("Error Prone está activo e falha a compilação num bug pattern conhecido")
    fun errorProneFailsOnKnownBugPattern() {
        writeSettings()
        writeBuild()
        writeSource(
            "src/main/java/fixture/SelfAssignmentSample.java",
            """
            package fixture;

            public final class SelfAssignmentSample {

                private int counter;

                public void reset() {
                    this.counter = this.counter;
                }
            }
            """.trimIndent(),
        )

        val result: BuildResult = runner("compileJava").buildAndFail()

        // SelfAssignment é severidade ERROR no Error Prone e o javac sozinho
        // não tem lint equivalente: a falha só pode vir do Error Prone.
        assertContains(result.output, "[SelfAssignment]")
    }

    @Test
    @DisplayName("-Werror transforma um warning do javac em falha de build")
    fun warningsAreErrors() {
        writeSettings()
        writeBuild()
        writeSource(
            "src/main/java/fixture/RawTypeSample.java",
            """
            package fixture;

            import java.util.ArrayList;
            import java.util.List;

            public final class RawTypeSample {

                public List<String> names() {
                    List raw = new ArrayList();
                    raw.add("tuprel");
                    return raw;
                }
            }
            """.trimIndent(),
        )

        val result: BuildResult = runner("compileJava").buildAndFail()

        assertContains(result.output, "warnings found and -Werror specified")
    }

    @Test
    @DisplayName("a fixture corre sob verificação estrita e rejeita um artefacto fora da baseline")
    fun rejectsDependencyOutsideVerificationBaseline() {
        writeSettings()
        /*
         * `commons-io` não pertence ao grafo do convention plugin e não está
         * na baseline. É um artefacto pequeno, o que mantém o teste barato.
         */
        writeBuild(
            """
            dependencies {
                testImplementation("commons-io:commons-io:2.16.1")
            }
            """.trimIndent(),
        )
        writeCalculator()
        writeSource(
            "src/test/java/fixture/CalculatorTest.java",
            """
            package fixture;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            class CalculatorTest {

                @Test
                void addsTwoNumbers() {
                    assertEquals(3, new Calculator().add(1, 2));
                }
            }
            """.trimIndent(),
        )

        val result: BuildResult = runner("compileTestJava").buildAndFail()

        assertContains(result.output, "Dependency verification failed")
        assertContains(result.output, "commons-io")
    }

    private fun writeSettings() {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
            }

            rootProject.name = "fixture"
            """.trimIndent() + "\n",
        )

        /*
         * Footprint explícito do daemon usado pelas builds TestKit.
         *
         * O default do Gradle (-Xms256m -Xmx512m -XX:MaxMetaspaceSize=384m) é
         * dimensionado para builds reais; estas fixtures compilam meia dúzia
         * de classes. Fixar valores pequenos mantém os testes executáveis em
         * máquinas com pouca memória disponível para commit, e garante que as
         * cinco fixtures partilham o mesmo daemon (a selecção de daemon é
         * feita pelos jvmargs).
         *
         * Isto configura apenas o processo de build das fixtures. Não altera
         * nenhum gate de qualidade nem configuração global de Gradle/JDK.
         */
        projectDir.resolve("gradle.properties").writeText(
            "org.gradle.jvmargs=-Xms64m -Xmx384m -XX:MaxMetaspaceSize=256m\n" +
                "org.gradle.workers.max=1\n",
        )

        writeVerificationMetadata()
    }

    /*
     * Uma build TestKit é uma build Gradle independente: não herda a
     * verification metadata deste repositório. Sem este passo, cada fixture
     * resolveria do Maven Central sem verificação nenhuma — incluindo
     * `error_prone_core`, que o convention plugin injecta e que corre dentro
     * do compilador.
     *
     * A baseline partilhada vive em src/test/resources e é gerada a partir de
     * uma build real que aplica `tuprel.java-conventions`. É copiada para cada
     * fixture em vez de duplicada por teste.
     */
    private fun writeVerificationMetadata() {
        val gradleDir = projectDir.resolve("gradle")
        gradleDir.mkdirs()

        val baseline = checkNotNull(
            javaClass.getResourceAsStream(VERIFICATION_METADATA_RESOURCE),
        ) {
            "baseline de verification metadata em falta: $VERIFICATION_METADATA_RESOURCE"
        }

        baseline.use { input ->
            gradleDir.resolve("verification-metadata.xml").outputStream().use(input::copyTo)
        }
    }

    private fun writeBuild(extra: String = "") {
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("tuprel.java-conventions")
            }

            // Tuning exclusivo da fixture. O Error Prone obriga o javac a
            // correr num worker do Gradle em fork, e o heap por omissão desse
            // worker é dimensionado para projectos reais. Fixar um valor
            // pequeno e igual em todas as fixtures mantém os testes
            // executáveis com pouca memória e permite reutilizar o mesmo
            // worker daemon entre testes. Não altera nada do que está a ser
            // verificado no convention plugin.
            tasks.withType<JavaCompile>().configureEach {
                options.forkOptions.memoryMaximumSize = "256m"
            }

            $extra
            """.trimIndent() + "\n",
        )
    }

    private fun writeCalculator() {
        writeSource(
            "src/main/java/fixture/Calculator.java",
            """
            package fixture;

            public final class Calculator {

                public int add(int first, int second) {
                    return first + second;
                }
            }
            """.trimIndent(),
        )
    }

    private fun writeSource(relativePath: String, content: String) {
        val file = projectDir.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content + "\n")
    }

    /*
     * `--dependency-verification strict` é explícito e não redundante: deixa
     * registado no comando que a fixture corre sob verificação e falha de
     * imediato se a baseline não cobrir um artefacto resolvido.
     */
    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments, "--dependency-verification", "strict")

    private fun assertContains(actual: String, expected: String) {
        assertTrue(actual.contains(expected), "output não contém \"" + expected + "\":\n" + actual)
    }

    private companion object {
        const val VERIFICATION_METADATA_RESOURCE = "/testkit/verification-metadata.xml"
    }
}
