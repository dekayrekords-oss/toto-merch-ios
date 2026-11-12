rootProject.name = "Meowmba-Multi"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Canonical Maven Central host: repo.maven.apache.org resolves
        // unreliably on some macOS resolvers, repo1.maven.org is the same CDN.
        maven(url = "https://repo1.maven.org/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven(url = "https://repo1.maven.org/maven2/")
        mavenCentral()
    }
}

include(":composeApp")
