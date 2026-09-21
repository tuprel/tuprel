plugins {
    id("tuprel.java-conventions")
    application
}

dependencies {
    implementation(project(":tuprel-schema"))
}

application {
    applicationName = "tuprel"
    mainClass.set("dev.tuprel.cli.TuprelCli")
}
