plugins {
    id("tuprel.java-conventions")
}

tasks.compileJava {
    options.release.set(17)
}

tasks.compileTestJava {
    options.release.set(17)
}
