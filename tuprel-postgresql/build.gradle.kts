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
