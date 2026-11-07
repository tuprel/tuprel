plugins {
    id("tuprel.java-conventions")
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("tuprelCodegen") {
        id = "dev.tuprel.codegen"
        implementationClass = "dev.tuprel.gradle.TuprelCodegenPlugin"
    }
}

dependencies {
    testImplementation(gradleTestKit())
}

tasks.test {
    dependsOn(":tuprel-cli:installDist", ":tuprel-runtime:jar", ":tuprel-sql:jar")
    val fixtureDirectories = listOf(
        "../tuprel-cli/build/install/tuprel/lib",
        "../tuprel-runtime/build/libs",
        "../tuprel-sql/build/libs",
    ).map { layout.projectDirectory.dir(it).asFile }
    inputs.files(fixtureDirectories).withNormalizer(ClasspathNormalizer::class)
    fixtureDirectories.forEachIndexed { index, directory ->
        systemProperty("tuprel.fixture.artifact.$index", directory.absolutePath)
    }
}
