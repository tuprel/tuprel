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
    providers.gradleProperty("tuprelConsumerPluginRepository").orNull?.let { repository ->
        inputs.dir(repository).withPathSensitivity(PathSensitivity.RELATIVE)
        systemProperty("tuprel.fixture.plugin.repository", repository)
    }
    providers.gradleProperty("tuprelConsumerGradleInstallation").orNull?.let { installation ->
        inputs.dir(installation).withPathSensitivity(PathSensitivity.RELATIVE)
        systemProperty("tuprel.fixture.gradle.installation", installation)
    }
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
