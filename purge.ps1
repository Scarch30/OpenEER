Write-Host "== Purge des caches Gradle, builds et libs locales ==" -ForegroundColor Cyan

# 1. Stopper Gradle
./gradlew --stop

# 2. Supprimer le cache Gradle global (~/.gradle)
$gradleCache = "$env:USERPROFILE\.gradle\caches"
if (Test-Path $gradleCache) {
    Write-Host "Suppression du cache Gradle : $gradleCache" -ForegroundColor Yellow
    Remove-Item -Recurse -Force "$gradleCache"
}

# 3. Supprimer le build cache de ton projet
$projectBuild = "build"
if (Test-Path $projectBuild) {
    Write-Host "Suppression du dossier build/ du projet" -ForegroundColor Yellow
    Remove-Item -Recurse -Force "$projectBuild"
}

# 4. Supprimer les builds de chaque module (app/, etc.)
Get-ChildItem -Directory -Recurse | Where-Object { $_.Name -eq "build" } | ForEach-Object {
    Write-Host "Suppression de $($_.FullName)" -ForegroundColor Yellow
    Remove-Item -Recurse -Force $_.FullName
}

# 5. Supprimer les AAR/JAR locaux (ex: Coquí, JNA)
$libs = "app\libs"
if (Test-Path $libs) {
    Write-Host "Nettoyage du dossier app/libs (AAR/JAR manuels)" -ForegroundColor Yellow
    Remove-Item -Recurse -Force "$libs\*.aar"
    Remove-Item -Recurse -Force "$libs\*.jar"
}

Write-Host "✅ Purge terminée ! Relance Android Studio et fais un 'Sync Project with Gradle'." -ForegroundColor Green
