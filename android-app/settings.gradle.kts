pluginManagement {
    // Resolve the implementation modules directly from their authoritative repositories.
    // Plugin portal and Maven Central marker POMs can differ for the same coordinate.
    repositories {
        // Kotlin implementation modules and metadata are authoritative on Maven Central.
        google { content { excludeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?") } }
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application" -> requested.version?.let { useModule("com.android.tools.build:gradle:$it") }
                "org.jetbrains.kotlin.plugin.compose" -> requested.version?.let { useModule("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$it") }
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "CodexAndroid"
include(":app")
