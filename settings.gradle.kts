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
        // ✅ AAR local Coquí (chemin relatif depuis la racine du projet)
        flatDir {
            // Si ton AAR est dans app/libs/
            dirs(file("app/libs"))
            // Si un jour tu déplaces l'AAR ailleurs, ajuste le chemin ici.
        }
    }
}

rootProject.name = "OpenEER"
include(":app")
