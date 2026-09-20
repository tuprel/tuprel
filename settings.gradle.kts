/*
 * Configuração raiz do build do Tuprel.
 *
 * Fase 0: apenas fundação de build. Nenhum módulo de produto é incluído
 * ainda. A estrutura alvo do monorepo está documentada em
 * docs/architecture/MODULE_BOUNDARIES.md e os módulos só serão declarados
 * quando existir código real para suportar.
 */

pluginManagement {
    /*
     * Fase 0 / Slice 5: `build-logic` é um included build que fornece os
     * convention plugins (`tuprel.java-conventions`). Não é um subprojecto de
     * produto e não aparece em `./gradlew projects`.
     *
     * Fica em `pluginManagement` porque é a partir daqui que os futuros
     * módulos resolvem o plugin pelo id, sem coordenadas nem versão.
     */
    includeBuild("build-logic")
}

rootProject.name = "tuprel"

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
