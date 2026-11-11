pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Error Prone's Gradle plugin is published only to the portal.
        gradlePluginPortal { content { includeGroup("net.ltgt.errorprone"); includeGroup("net.ltgt.gradle") } }
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "klethron-tsnet"
include(":tsnet", ":demo")
