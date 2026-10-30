plugins {
    id("tuprel.java-conventions")
    application
}

dependencies {
    implementation(project(":tuprel-schema"))
    implementation(project(":tuprel-codegen-java"))
    implementation(project(":tuprel-migrate"))
}

tasks.jar {
    manifest.attributes["Implementation-Version"] =
        project.version.toString().takeUnless { it == "unspecified" } ?: "development"
}

application {
    applicationName = "tuprel"
    mainClass.set("dev.tuprel.cli.TuprelCli")
}
