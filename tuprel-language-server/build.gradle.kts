plugins {
    id("tuprel.java-conventions")
    application
}

dependencies {
    implementation(project(":tuprel-schema"))
    implementation(libs.gson)
}

tasks.compileJava {
    options.release.set(17)
}

tasks.named<JavaCompile>("compileIntegrationTestJava") {
    options.release.set(17)
}

application {
    mainClass.set("dev.tuprel.lsp.TuprelLanguageServer")
}

tasks.named<Test>("integrationTest") {
    val distribution = tasks.installDist
    dependsOn(distribution)
    inputs.files(distribution)
    systemProperty("tuprel.lsp.lib", layout.buildDirectory.dir("install/tuprel-language-server/lib").get().asFile.absolutePath)
    providers.gradleProperty("lspTestJava").orNull?.let { version ->
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(version.toInt()))
        })
    }
}
