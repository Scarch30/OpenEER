val sdkDirectory = rootDir.resolve("android-sdk")
if (!sdkDirectory.exists()) {
    sdkDirectory.mkdirs()
}

val licensesDirectory = sdkDirectory.resolve("licenses")
if (!licensesDirectory.exists()) {
    licensesDirectory.mkdirs()
}

val sdkLicense = licensesDirectory.resolve("android-sdk-license")
if (!sdkLicense.exists()) {
    sdkLicense.writeText(
        """
d56f5187479451eabf01fb78af6dfcb131a6481e
24333f8a63b6825ea9c5514f83c2829b004d1fee
""".trimIndent()
    )
}

val ndkLicense = licensesDirectory.resolve("android-sdk-ndk-license")
if (!ndkLicense.exists()) {
    ndkLicense.writeText("24333f8a63b6825ea9c5514f83c2829b004d1fee\n")
}

val localPropertiesFile = rootDir.resolve("local.properties")
if (!localPropertiesFile.exists()) {
    val sdkPath = sdkDirectory.absolutePath.replace("\\", "\\\\")
    localPropertiesFile.writeText("sdk.dir=$sdkPath\n")
}

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
        maven(url = "https://jitpack.io")
        // Dépôts Maven habituels uniquement, le modèle Vosk est fourni via assets.
    }
}

rootProject.name = "OpenEER"
include(":app")
