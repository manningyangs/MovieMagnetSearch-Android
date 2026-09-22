pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Coil snapshot repo (optional)
        // maven("https://oss.sonatype.org/content/repositories/snapshots")
    }
}

rootProject.name = "MovieMagnetSearch"
include(":app")
