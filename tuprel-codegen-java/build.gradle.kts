plugins {
    id("tuprel.java-conventions")
}

dependencies {
    api(project(":tuprel-schema"))
    testImplementation(project(":tuprel-runtime"))
}
