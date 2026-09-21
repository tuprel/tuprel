/*
 * tuprel.java-conventions
 *
 * Baseline partilhado dos futuros módulos Java do Tuprel: toolchain, política
 * de compilador, static analysis, testes e reprodutibilidade de artefactos.
 *
 * Este plugin é aplicado pelos módulos de produto da Fase 1 e continua a
 * servir de baseline para os módulos futuros, sem decisões locais duplicadas.
 *
 * Deliberadamente ausente: Spring, Jakarta Persistence, Hibernate, drivers de
 * base de dados, Testcontainers e qualquer dependência de produto Tuprel.
 * Testcontainers entra quando existirem integration tests PostgreSQL reais.
 */

import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

plugins {
    /*
     * `java-library`: os módulos Tuprel são bibliotecas consumidas por
     * terceiros, com separação explícita entre `api` e `implementation`.
     */
    `java-library`

    // Static analysis dentro do próprio compilador.
    id("net.ltgt.errorprone")
}

/*
 * Cada módulo de produto tem o seu próprio lock state. Activar locking na
 * convenção garante que dependências injectadas por este plugin (Error Prone e
 * JUnit) e dependências futuras do módulo ficam cobertas pela mesma política.
 */
dependencyLocking {
    lockAllConfigurations()
}

/*
 * Versões fixas, sem ranges, sem `+`, sem versões dinâmicas, sem snapshots e
 * sem RC/milestone (ver .claude/rules/security.md e gradle/libs.versions.toml).
 *
 * Estas duas constantes são injectadas nos módulos consumidores, por isso têm
 * de viver dentro do próprio plugin: um precompiled script plugin não consegue
 * usar os acessores type-safe de um version catalog.
 *
 * error_prone_core 2.50.0: release estável (2026-06-10), licença Apache-2.0,
 * JDK mínimo 21 desde 2.43.0 — coerente com ADR-0002.
 *
 * junit-bom 5.14.4: última release estável da linha JUnit 5 (2026-04-26).
 * Existe já uma linha JUnit 6.x estável; a passagem para 6.x é uma decisão
 * deliberada de baseline e não um efeito lateral desta fatia.
 */
val errorProneVersion = "2.50.0"
val junitBomVersion = "5.14.4"

extensions.configure<JavaPluginExtension> {
    /*
     * Toolchain fixa garante que a compilação não depende do JDK com que o
     * Gradle arrancou.
     */
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

/*
 * Integration tests num source set próprio: src/integrationTest/java e
 * src/integrationTest/resources.
 *
 * Ficam separados dos unit tests porque vão depender de recursos externos
 * (PostgreSQL real, em fase posterior) e não devem tornar o ciclo de unit
 * tests lento nem não determinístico.
 */
val sourceSetContainer = extensions.getByType<SourceSetContainer>()
val mainSourceSet = sourceSetContainer.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
val integrationTestSourceSet: SourceSet = sourceSetContainer.create("integrationTest") {
    // Um source set novo não vê o output de `main` por omissão.
    compileClasspath += mainSourceSet.output
    runtimeClasspath += mainSourceSet.output
}

/*
 * Os integration tests herdam as dependências dos unit tests (incluindo a
 * baseline JUnit declarada abaixo) e acrescentam apenas o que for específico
 * através de `integrationTestImplementation`.
 */
configurations
    .getByName(integrationTestSourceSet.implementationConfigurationName)
    .extendsFrom(configurations.getByName("testImplementation"))
configurations
    .getByName(integrationTestSourceSet.compileOnlyConfigurationName)
    .extendsFrom(configurations.getByName("testCompileOnly"))
configurations
    .getByName(integrationTestSourceSet.runtimeOnlyConfigurationName)
    .extendsFrom(configurations.getByName("testRuntimeOnly"))

dependencies {
    errorprone("com.google.errorprone:error_prone_core:$errorProneVersion")

    testImplementation(platform("org.junit:junit-bom:$junitBomVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    /*
     * Desde o Gradle 9 o `junit-platform-launcher` tem de estar declarado
     * explicitamente no runtime classpath de teste; deixou de ser injectado
     * automaticamente. O BOM mantém a versão do launcher (linha 1.x) alinhada
     * com a do Jupiter (linha 5.x) sem fixar dois números diferentes à mão.
     */
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    // Encoding explícito: o resultado não pode depender do locale da máquina.
    options.encoding = "UTF-8"

    /*
     * `release` garante o contrato de bytecode/API de Java 21 mesmo que a
     * compilação venha a correr num JDK posterior (ADR-0002).
     */
    options.release.set(21)

    /*
     * Política de compilador: todos os lints do javac ligados e warnings
     * tratados como erro. Nenhuma categoria de lint está desactivada.
     */
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))

    options.errorprone {
        /*
         * Código gerado (Fase 2 em diante) não deve produzir warnings que
         * falhem a build de quem consome o Tuprel: o autor do código gerado é
         * o gerador, não o utilizador. Findings de severidade ERROR continuam
         * a falhar.
         */
        disableWarningsInGeneratedCode.set(true)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Executa os integration tests do módulo."
    group = LifecycleBasePlugin.VERIFICATION_GROUP

    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath

    /*
     * `shouldRunAfter` e não `mustRunAfter`: ordena os unit tests primeiro sem
     * impedir que alguém execute `integrationTest` isoladamente.
     */
    shouldRunAfter(tasks.named("test"))
}

/*
 * Integration tests fazem parte da verificação. Sem isto, `check` passaria
 * ignorando-os.
 */
tasks.named("check") {
    dependsOn(integrationTestTask)
}

/*
 * Artefactos reproduzíveis: o mesmo input tem de produzir o mesmo ficheiro.
 * Usa apenas os mecanismos suportados pelo Gradle; sem manipulação manual de
 * entradas de arquivo.
 */
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
