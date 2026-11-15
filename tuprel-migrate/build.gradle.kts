import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    id("tuprel.java-conventions")
}

tasks.compileJava {
    options.release.set(17)
}

tasks.compileTestJava {
    options.release.set(17)
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}

tasks.named("check") {
    dependsOn(tasks.javadoc)
}
