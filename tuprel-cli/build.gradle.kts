plugins {
    id("tuprel.java-conventions")
    application
}

dependencies {
    implementation(project(":tuprel-schema"))
    implementation(project(":tuprel-codegen-java"))
}

application {
    applicationName = "tuprel"
    mainClass.set("dev.tuprel.cli.TuprelCli")
}
