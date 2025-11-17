import java.io.File

plugins {
    id("com.android.application") version "8.12.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}

// Détection simple de Windows
val isWindows: Boolean =
    System.getProperty("os.name").lowercase().contains("windows")

gradle.settingsEvaluated {
    val sdkDir = File(rootDir, "android_sdk")
    val autoSetupScript = File(rootDir, "auto_setup_android_sdk.sh")

    // ⚠️ Ne lance le script auto_setup_android_sdk.sh que si on N'EST PAS sur Windows
    if (!isWindows && !sdkDir.exists() && autoSetupScript.exists()) {
        val process = ProcessBuilder("bash", autoSetupScript.absolutePath)
            .directory(rootDir)
            .inheritIO()
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("auto_setup_android_sdk.sh failed with exit code $exitCode")
        }
    }

    val localProperties = File(rootDir, "local.properties")
    if (!localProperties.exists() && sdkDir.exists()) {
        val escapedPath = sdkDir.absolutePath.replace("\\", "\\\\")
        localProperties.writeText("sdk.dir=$escapedPath\n")
    }
}

val setupAndroidSdk by tasks.registering(Exec::class) {
    val script = rootProject.file("auto_setup_android_sdk.sh")

    inputs.file(script)
    outputs.dir(rootProject.file("android_sdk"))

    workingDir = rootProject.projectDir

    // Commande réelle (Linux / CI)
    commandLine("bash", script.absolutePath)

    isIgnoreExitValue = false
    description = "Installs and configures the Android SDK required for the build."
    group = "setup"

    // ⛔ Sur Windows, ou si le script n'existe pas, la tâche est SKIPPED (pas exécutée)
    onlyIf {
        !isWindows && script.exists()
    }
}

gradle.projectsEvaluated {
    // On ne branche la dépendance automatique que si on n'est PAS sur Windows
    if (!isWindows) {
        allprojects.forEach { project ->
            project.tasks.matching { task ->
                val name = task.name
                name.equals("test", ignoreCase = true) || name.endsWith("Test", ignoreCase = true)
            }.configureEach {
                dependsOn(setupAndroidSdk)
            }
            project.tasks.matching { task ->
                val name = task.name
                name.startsWith("process", ignoreCase = true) &&
                        name.endsWith("Resources", ignoreCase = true)
            }.configureEach {
                dependsOn(setupAndroidSdk)
            }
        }
    }
}

