rootProject.name = "tuprel-playground"

// Consume the library as an external application without publishing local Maven artifacts.
includeBuild("../..")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
