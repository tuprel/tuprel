plugins {
    id("tuprel.java-conventions")
}

val consumerTransactionFixtures by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    consumerTransactionFixtures("org.springframework:spring-jdbc:7.0.10")
    api(project(":tuprel-runtime"))
    implementation(project(":tuprel-postgresql"))
    implementation("org.springframework.boot:spring-boot-autoconfigure:4.1.2")
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
    val stagedClasspath = files(tasks.jar, configurations.runtimeClasspath, consumerTransactionFixtures)
    dependsOn(stagedClasspath)
    inputs.files(stagedClasspath).withNormalizer(ClasspathNormalizer::class)
    systemProperty("tuprel.spring.fixture.classpath", stagedClasspath.asPath)
}
