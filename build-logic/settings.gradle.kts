/*
 * Configuração do included build `build-logic`.
 *
 * `build-logic` NÃO é um módulo de produto Tuprel. É a build que produz os
 * convention plugins usados pelos futuros módulos Java. Continua a existir
 * zero módulos de produto no build raiz.
 *
 * Um included build é independente: o `dependencyResolutionManagement` e o
 * `pluginManagement` da raiz não governam a resolução aqui. Por isso os
 * repositórios necessários são declarados explicitamente neste ficheiro.
 */

pluginManagement {
    /*
     * Hoje esta build só aplica o plugin `kotlin-dsl`, que vem dentro da
     * distribuição do Gradle e não é resolvido pela rede. O repositório fica
     * declarado explicitamente para que a superfície de plugin resolution seja
     * visível e auditável quando a fatia de dependency verification chegar.
     */
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    /*
     * Nenhum subprojecto de build-logic pode declarar repositórios próprios,
     * pela mesma razão que na raiz: manter a superfície de supply chain num
     * único ponto auditável.
     */
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        /*
         * Maven Central serve o Kotlin stdlib exigido pelo `kotlin-dsl` e as
         * dependências de teste (JUnit).
         */
        mavenCentral()

        /*
         * O Gradle Plugin Portal é necessário e não é redundante:
         * `net.ltgt.gradle:gradle-errorprone-plugin` NÃO está publicado no
         * Maven Central (apenas as versões 0.0.x de 2015 lá existem). O
         * artefacto actual é servido por https://plugins.gradle.org/m2/.
         */
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"
