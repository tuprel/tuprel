import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    id("tuprel.java-conventions")
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}

tasks.named("check") {
    dependsOn(tasks.javadoc)
}
