/*
 * Configuração raiz do build do Tuprel.
 *
 * As Fases 1-3 introduzem módulos de schema, codegen e runtime PostgreSQL.
 * A estrutura alvo do
 * monorepo continua documentada em docs/architecture/MODULE_BOUNDARIES.md.
 */

pluginManagement {
    /*
     * Fase 0 / Slice 5: `build-logic` é um included build que fornece os
     * convention plugins (`tuprel.java-conventions`). Não é um subprojecto de
     * produto e não aparece em `./gradlew projects`.
     *
     * Fica em `pluginManagement` porque é a partir daqui que os
     * módulos resolvem o plugin pelo id, sem coordenadas nem versão.
     */
    includeBuild("build-logic")
}

rootProject.name = "tuprel"

include("tuprel-schema")
include("tuprel-cli")
include("tuprel-codegen-java")
include("tuprel-sql")
include("tuprel-runtime")
include("tuprel-postgresql")

dependencyResolutionManagement {
    /*
     * Resolução de dependências centralizada: nenhum subprojecto pode declarar
     * repositórios próprios. Mantém a superfície de supply chain num único
     * ponto auditável e evita que um módulo futuro introduza silenciosamente
     * uma origem não revista.
     */
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        mavenCentral()
    }

    /*
     * O catálogo de versões em gradle/libs.versions.toml é detectado
     * automaticamente pelo Gradle; não precisa de declaração explícita.
     */
}
