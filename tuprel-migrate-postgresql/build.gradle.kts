import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    id("tuprel.java-conventions")
}

dependencies {
    api(project(":tuprel-migrate"))
    api(project(":tuprel-sql"))
    api(project(":tuprel-schema"))
    integrationTestImplementation("org.postgresql:postgresql:42.7.14")
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
}

sourceSets.named("integrationTest") {
    java.srcDir("../test-support/src/main/java")
    java.srcDir("../test-support/src/integrationTest/java")
}

tasks.named<Test>("integrationTest") {
    // Exercise the installed adapter artifact as well as the PostgreSQL protocol.
    val adapterJar = tasks.jar.flatMap { it.archiveFile }
    val managedCli = providers.gradleProperty("tuprelTestManagedCli")
    val installedJars = if (managedCli.isPresent) fileTree("${managedCli.get()}/lib") { include("*.jar") } else files(adapterJar)
    classpath = files(installedJars, classpath - sourceSets.main.get().output)
    systemProperty("tuprel.test.database", providers.gradleProperty("tuprelTestDatabase").getOrElse("local"))
    systemProperty("tuprel.test.env.file", rootProject.layout.projectDirectory
        .file("examples/tuprel-playground/.env").asFile.absolutePath)
    systemProperty("tuprel.test.publisher", providers.gradleProperty("tuprelTestPublisher").getOrElse(""))
    systemProperty("tuprel.test.publicationVolume", providers.gradleProperty("tuprelTestPublicationVolume").getOrElse(""))
    if (managedCli.isPresent) {
        dependsOn(":tuprel-postgresql:jar", ":tuprel-runtime:jar")
        systemProperty("tuprel.test.staged.cli", managedCli.get())
        systemProperty("tuprel.test.staged.runtime", project(":tuprel-runtime").layout.buildDirectory.file("libs/tuprel-runtime.jar").get().asFile.absolutePath)
        systemProperty("tuprel.test.staged.postgresql", project(":tuprel-postgresql").layout.buildDirectory.file("libs/tuprel-postgresql.jar").get().asFile.absolutePath)
        systemProperty("tuprel.test.staged.example", rootProject.layout.projectDirectory.dir("examples/staged-consumer").asFile.absolutePath)
    }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Integration tests use external database state") { true }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}

tasks.jar {
    manifest.attributes["Tuprel-Managed-Artifact"] = "dev.tuprel:tuprel-migrate-postgresql"
    manifest.attributes["Implementation-Version"] =
        project.version.toString().takeUnless { it == "unspecified" } ?: "development"
}

tasks.named("check") {
    dependsOn(tasks.javadoc)
}
