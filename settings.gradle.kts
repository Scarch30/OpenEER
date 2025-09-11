pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // On garde le mode strict (pas de repos au niveau module)
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Dépôts Maven habituels uniquement, le modèle Vosk est fourni via assets.
    }
}

rootProject.name = "OpenEER"
include(":app")
