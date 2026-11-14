plugins {
    id("tuprel.java-conventions")
    application
}

dependencies {
    implementation(project(":tuprel-schema"))
    implementation(libs.gson)
}

application {
    mainClass.set("dev.tuprel.lsp.TuprelLanguageServer")
}

tasks.named<Test>("integrationTest") {
    val distribution = tasks.installDist
    dependsOn(distribution)
    inputs.files(distribution)
    systemProperty("tuprel.lsp.lib", layout.buildDirectory.dir("install/tuprel-language-server/lib").get().asFile.absolutePath)
}
