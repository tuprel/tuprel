import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    id("tuprel.java-conventions")
}

dependencies {
    api(project(":tuprel-migrate"))
    implementation(project(":tuprel-sql"))
    integrationTestImplementation("org.postgresql:postgresql:42.7.14")
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
}

sourceSets.named("integrationTest") {
    java.srcDir("../test-support/src/main/java")
    java.srcDir("../test-support/src/integrationTest/java")
}

tasks.named<Test>("integrationTest") {
    systemProperty("tuprel.test.database", providers.gradleProperty("tuprelTestDatabase").getOrElse("local"))
    systemProperty("tuprel.test.env.file", rootProject.layout.projectDirectory
        .file("examples/tuprel-playground/.env").asFile.absolutePath)
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Integration tests use external database state") { true }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}

tasks.named("check") {
    dependsOn(tasks.javadoc)
}
