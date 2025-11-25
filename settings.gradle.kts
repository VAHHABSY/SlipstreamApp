pluginManagement {
    repositories {
        // Essential repository for Android Gradle Plugin
        google()
        // Essential repository for general Gradle plugins (like Kotlin)
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // Ensures dependencies are resolved from the defined repositories
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SlipstreamApp"
include(":app")
