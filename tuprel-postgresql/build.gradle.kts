plugins {
    id("tuprel.java-conventions")
}

dependencies {
    api(project(":tuprel-runtime"))
    implementation(project(":tuprel-sql"))
    runtimeOnly("org.postgresql:postgresql:42.7.13")

    integrationTestImplementation("org.postgresql:postgresql:42.7.13")
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
}

/*
 * Os integration tests do cliente gerado usam código real produzido pelo
 * `tuprel generate` da CLI a partir de src/integrationTest/tuprel/schema.tuprel,
 * exactamente como num projecto consumidor: nada é gerado por reflection nem
 * compilado em runtime. O classpath do gerador contém apenas módulos do
 * próprio repositório, sem artefactos externos novos.
 */
val tuprelGenerator = configurations.dependencyScope("tuprelGenerator")
val tuprelGeneratorClasspath = configurations.resolvable("tuprelGeneratorClasspath") {
    extendsFrom(tuprelGenerator.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

dependencies {
    tuprelGenerator(project(":tuprel-cli"))
}

val generateIntegrationTestClient = tasks.register<JavaExec>("generateIntegrationTestClient") {
    description = "Gera o cliente Java usado pelos integration tests com a CLI do Tuprel."
    group = "build"
    classpath = tuprelGeneratorClasspath.get()
    mainClass.set("dev.tuprel.cli.TuprelCli")
    // Caminhos relativos ao projecto: a CLI escreve em build/generated/sources/tuprel/main.
    workingDir = layout.projectDirectory.asFile
    args("generate", "src/integrationTest/tuprel/schema.tuprel")
    inputs.file(layout.projectDirectory.file("src/integrationTest/tuprel/schema.tuprel"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.buildDirectory.dir("generated/sources/tuprel/main"))
}

sourceSets.named("integrationTest") {
    java.srcDir(generateIntegrationTestClient.map { it.outputs.files.singleFile })
}
