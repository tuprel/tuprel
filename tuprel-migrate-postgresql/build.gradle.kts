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
    classpath = files(adapterJar) + (classpath - sourceSets.main.get().output)
    systemProperty("tuprel.test.database", providers.gradleProperty("tuprelTestDatabase").getOrElse("local"))
    systemProperty("tuprel.test.env.file", rootProject.layout.projectDirectory
        .file("examples/tuprel-playground/.env").asFile.absolutePath)
    systemProperty("tuprel.test.publisher", providers.gradleProperty("tuprelTestPublisher").getOrElse(""))
    systemProperty("tuprel.test.publicationVolume", providers.gradleProperty("tuprelTestPublicationVolume").getOrElse(""))
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
