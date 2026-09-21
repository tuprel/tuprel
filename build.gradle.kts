/*
 * Projecto raiz do Tuprel.
 *
 * Este projecto é agregador, não um módulo Java de produto. Não
 * aplica `java`, `java-library`, `application`, publicação nem assinatura.
 * Os módulos Java aplicam as convenções partilhadas individualmente.
 */

/*
 * Dependency locking do classpath de plugins deste script.
 *
 * O projecto raiz não tem NENHUMA configuração de dependências de projecto
 * (`./gradlew dependencies` responde "No configurations"): o plugin `base` não
 * declara dependências e os passos de Spotless em uso são higiene de texto
 * pura, sem artefactos externos. Por isso não existe aqui nada para
 * `dependencyLocking { lockAllConfigurations() }` bloquear, e declará-lo seria
 * configuração decorativa.
 *
 * O que existe de facto é o classpath de plugins: `alias(libs.plugins.spotless)`
 * resolve o Spotless e todo o seu grafo transitivo (jgit, durian, slf4j,
 * commons-codec, ...). Esse grafo é real, vem da rede e é o que fica travado
 * aqui.
 *
 * Locking fixa as versões seleccionadas. NÃO prova integridade dos artefactos;
 * isso é dependency verification, uma fatia posterior.
 */
buildscript {
    configurations["classpath"].resolutionStrategy.activateDependencyLocking()
}

plugins {
    /*
     * Fornece o lifecycle agregador: clean, assemble, check e build.
     * Deliberadamente sem `java`: código de produto pertence aos subprojects.
     */
    base

    // Gate de formatação determinística para ficheiros de build/configuração.
    alias(libs.plugins.spotless)
}

/*
 * Coordenada PROVISÓRIA.
 *
 * `dev.tuprel` é um marcador de trabalho enquanto a revisão de nome, domínio e
 * enquadramento legal está pendente (ver docs/product/NAMING_AND_LEGAL.md).
 *
 * NÃO deve ser interpretada como coordenada de publicação aprovada. Nenhum
 * artefacto pode ser publicado com esta coordenada antes dessa decisão estar
 * fechada.
 */
group = "dev.tuprel"

subprojects {
    group = rootProject.group
}

/*
 * `version` é intencionalmente omitida neste slice. O baseline de versionamento
 * e changelog é um item próprio da Fase 0 e será definido quando existir algo
 * versionável.
 */

/*
 * Agregação do lifecycle: `check` da raiz tem de verificar também o included
 * build `build-logic`.
 *
 * Um included build NÃO é verificado só por estar incluído. Sem esta ligação
 * explícita, `./gradlew check` na raiz passaria sem nunca compilar nem testar
 * os convention plugins, e uma regressão em `tuprel.java-conventions` só
 * apareceria quando o primeiro módulo de produto a aplicasse.
 *
 * `base` também faz `build` depender de `check`, por isso `build` na raiz
 * herda a mesma verificação.
 */
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
    dependsOn(":tuprel-schema:check", ":tuprel-cli:check")
}

tasks.named("assemble") {
    dependsOn(":tuprel-schema:assemble", ":tuprel-cli:assemble")
}

/*
 * Spotless com âmbito deliberadamente conservador.
 *
 * O objectivo desta fase NÃO é reformatar o corpus de documentação existente
 * (docs/, plans/, project/, templates/, Markdown na raiz). O objectivo é ter um
 * gate determinístico sobre os ficheiros de engenharia criados pela fundação.
 *
 * Apenas passos de higiene de texto são usados. Nenhum motor de formatação
 * adicional (ktlint, ktfmt, detekt, google-java-format, palantir-java-format) é
 * introduzido: a formatação de Java será uma decisão explícita quando existir
 * código Java.
 */
spotless {
    // Codificação explícita, para o resultado não depender do locale da máquina.
    encoding("UTF-8")

    /*
     * Ficheiros Gradle Kotlin DSL da raiz e do included build `build-logic`,
     * incluindo o convention plugin precompilado. Expansão deliberadamente
     * limitada aos ficheiros de build criados pela fundação: as fontes Kotlin
     * de teste de build-logic ficam fora, porque não são Gradle DSL e
     * exigiriam uma decisão própria sobre motor de formatação Kotlin.
     */
    format("gradleKotlinDsl") {
        target(
            "settings.gradle.kts",
            "build.gradle.kts",
            "build-logic/settings.gradle.kts",
            "build-logic/build.gradle.kts",
            "build-logic/src/main/kotlin/*.gradle.kts",
        )
        leadingTabsToSpaces(4)
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("versionCatalog") {
        target("gradle/libs.versions.toml")
        leadingTabsToSpaces(2)
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("gradleProperties") {
        target("gradle.properties", "build-logic/gradle.properties")
        // Sem normalização de indentação: em ficheiros .properties o espaço
        // inicial tem significado para o parser.
        trimTrailingWhitespace()
        endWithNewline()
    }

    /*
     * Workflows de CI versionados em `.github/`. O âmbito permanece limitado
     * à higiene de YAML; não introduz um formatter geral de documentação.
     */
    format("githubWorkflows") {
        target(".github/**/*.yml", ".github/**/*.yaml")
        leadingTabsToSpaces(2)
        trimTrailingWhitespace()
        endWithNewline()
    }

    /*
     * Java da Fase 1. O projecto mantém o âmbito conservador: apenas higiene
     * determinística de whitespace, sem acrescentar um formatter de Java.
     */
    format("javaSources") {
        target("tuprel-schema/src/**/*.java", "tuprel-cli/src/**/*.java")
        leadingTabsToSpaces(4)
        trimTrailingWhitespace()
        endWithNewline()
    }
}
