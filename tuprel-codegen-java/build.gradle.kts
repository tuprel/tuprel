plugins {
    id("tuprel.java-conventions")
}

tasks.compileJava {
    options.release.set(17)
}

tasks.compileTestJava {
    options.release.set(17)
}

dependencies {
    api(project(":tuprel-schema"))
    testImplementation(project(":tuprel-runtime"))
}
