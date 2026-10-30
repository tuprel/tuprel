plugins {
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

val generator = configurations.create("generator") {
    isCanBeConsumed = false
}

dependencies {
    implementation("dev.tuprel:tuprel-postgresql:unspecified")
    implementation("org.postgresql:postgresql:42.7.14")
    add(generator.name, "dev.tuprel:tuprel-cli:unspecified")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyLocking { lockAllConfigurations() }

val validateSchema = tasks.register<JavaExec>("validateSchema") {
    classpath = generator
    mainClass.set("dev.tuprel.cli.TuprelCli")
    workingDir = layout.projectDirectory.asFile
    args("validate", "tuprel/schema.tuprel")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

val generateClient = tasks.register<JavaExec>("generateClient") {
    dependsOn(validateSchema)
    classpath = generator
    mainClass.set("dev.tuprel.cli.TuprelCli")
    workingDir = layout.projectDirectory.asFile
    args("generate", "tuprel/schema.tuprel")
    inputs.file("tuprel/schema.tuprel").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.buildDirectory.dir("generated/sources/tuprel/main"))
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

sourceSets.main {
    java.srcDir(generateClient.map { it.outputs.files.singleFile })
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

application {
    mainClass.set("dev.tuprel.playground.Playground")
}

tasks.test {
    useJUnitPlatform()
}
