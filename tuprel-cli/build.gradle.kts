plugins {
    id("tuprel.java-conventions")
    application
}

dependencies {
    implementation(project(":tuprel-schema"))
    implementation(project(":tuprel-codegen-java"))
    implementation(project(":tuprel-migrate"))
    implementation(project(":tuprel-migrate-postgresql"))
    runtimeOnly("org.postgresql:postgresql:42.7.14")
    integrationTestImplementation("org.postgresql:postgresql:42.7.14")
    integrationTestImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
}

sourceSets.named("integrationTest") {
    java.srcDir("../test-support/src/main/java")
    java.srcDir("../test-support/src/integrationTest/java")
}

tasks.named<Test>("integrationTest") {
    dependsOn(tasks.installDist)
    systemProperty("tuprel.test.database", providers.gradleProperty("tuprelTestDatabase").getOrElse("local"))
    systemProperty("tuprel.test.env.file", rootProject.layout.projectDirectory
        .file("examples/tuprel-playground/.env").asFile.absolutePath)
    systemProperty("tuprel.cli.launcher", layout.buildDirectory.file("install/tuprel/bin/tuprel").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Integration tests use external database state") { true }
}

tasks.jar {
    manifest.attributes["Implementation-Version"] =
        project.version.toString().takeUnless { it == "unspecified" } ?: "development"
}

application {
    applicationName = "tuprel"
    mainClass.set("dev.tuprel.cli.TuprelCli")
}
