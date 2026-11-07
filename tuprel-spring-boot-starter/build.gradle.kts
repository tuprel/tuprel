plugins {
    id("tuprel.java-conventions")
}

dependencies {
    api(project(":tuprel-runtime"))
    implementation(project(":tuprel-postgresql"))
    implementation("org.springframework.boot:spring-boot-autoconfigure:4.1.2")
}

tasks.test {
    val stagedClasspath = files(tasks.jar, configurations.runtimeClasspath)
    dependsOn(stagedClasspath)
    inputs.files(stagedClasspath).withNormalizer(ClasspathNormalizer::class)
    systemProperty("tuprel.spring.fixture.classpath", stagedClasspath.asPath)
}
