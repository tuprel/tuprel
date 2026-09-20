/*
 * Build dos convention plugins do Tuprel.
 *
 * Produz apenas plugins de build. Nenhum código de produto vive aqui.
 */

import java.time.Duration

plugins {
    /*
     * `kotlin-dsl` vem com o Gradle e habilita precompiled script plugins em
     * src/main/kotlin. Aplica também `java-gradle-plugin`, o que expõe o
     * classpath do plugin aos testes TestKit via `withPluginClasspath()`.
     */
    `kotlin-dsl`
}

dependencies {
    /*
     * Integração Gradle <-> Error Prone. Licença Apache-2.0.
     *
     * Esta é a ÚNICA declaração da versão do plugin de integração Error Prone
     * no repositório. A versão do Error Prone em si é declarada no convention
     * plugin, porque é injectada nos módulos consumidores e não no classpath
     * desta build.
     *
     * 5.1.1 é release estável (2026-08-25). Mínimos declarados pelo projecto:
     * Gradle 7.1 e JDK 11; a partir de 4.4.0 o plugin passa incondicionalmente
     * a flag `-XDaddTypeAnnotationsToSymbol=true` exigida pelo Error Prone
     * >= 2.46.0 em JDK 21, e em JDK 16+ activa fork e os `--add-exports` /
     * `--add-opens` necessários ao compilador.
     */
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:5.1.1")

    /*
     * Testes desta build. A versão do JUnit aparece deliberadamente em dois
     * sítios pinados: aqui (testes do próprio build-logic) e no convention
     * plugin (baseline injectada nos módulos consumidores). Um included build
     * não herda o version catalog da raiz, e partilhar estas duas constantes
     * através de infraestrutura adicional custaria mais do que resolve.
     */
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Gradle TestKit: executa builds reais contra o convention plugin.
    testImplementation(gradleTestKit())
}

tasks.named<Test>("test") {
    useJUnitPlatform()

    /*
     * O JVM de teste só orquestra o TestKit: a build real das fixtures corre
     * noutro processo. Um heap pequeno e explícito deixa memória disponível
     * para esse processo em vez de a reservar aqui sem uso.
     */
    maxHeapSize = "256m"

    /*
     * Directório partilhado de TestKit. Sem isto cada execução usa um Gradle
     * user home temporário diferente e volta a descarregar Error Prone e JUnit
     * para cada teste.
     */
    systemProperty(
        "org.gradle.testkit.dir",
        layout.buildDirectory.dir("test-kit").get().asFile.absolutePath,
    )

    /*
     * Builds TestKit reais são lentos comparados com unit tests puros.
     * O timeout evita que uma regressão de configuração bloqueie o `check`
     * indefinidamente.
     */
    timeout.set(Duration.ofMinutes(20))
}
