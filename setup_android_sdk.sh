#!/usr/bin/env bash
set -euo pipefail

finish() {
    local exit_code=$1
    if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
        exit "$exit_code"
    else
        return "$exit_code"
    fi
}

# --- Configuration ---
SDK_DIR="android_sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_TOOLS_ZIP="cmdline-tools.zip"

# Fonction pour extraire une valeur de build.gradle.kts
extract_gradle_property() {
    local property_name=$1
    local gradle_file="app/build.gradle.kts"
    local matches=""

    if [[ -f "$gradle_file" ]]; then
        matches=$(grep -m 1 "$property_name" "$gradle_file" || true)
    fi

    if [[ -n "$matches" ]]; then
        echo "$matches" | sed -n "s/.* = //p" | tr -d '"'
    fi
}

# --- Vérification initiale ---
if [ -d "$SDK_DIR" ]; then
    echo "✅ Le dossier '$SDK_DIR' existe déjà. L'installation est probablement déjà faite."
    echo "Pour forcer une réinstallation, supprimez le dossier '$SDK_DIR' et relancez le script."
    # Exporte les variables pour la session courante si le SDK est déjà là
    export ANDROID_HOME="$(pwd)/$SDK_DIR"
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
    echo "✅ Les variables d'environnement ont été configurées pour la session actuelle."
    finish 0
fi

echo "--- Démarrage de l'installation du SDK Android ---"

# --- Extraction des versions depuis Gradle ---
echo "⚙️  Lecture des versions depuis app/build.gradle.kts..."
COMPILE_SDK=$(extract_gradle_property "compileSdk")
BUILD_TOOLS_VERSION=$(extract_gradle_property "buildToolsVersion")

# Si buildToolsVersion n'est pas trouvé, utiliser une version par défaut stable
if [ -z "$BUILD_TOOLS_VERSION" ]; then
    BUILD_TOOLS="35.0.0" # Version recommandée pour l'outil de build
else
    BUILD_TOOLS=$BUILD_TOOLS_VERSION
fi

if [ -z "$COMPILE_SDK" ]; then
    echo "❌ Erreur: Impossible de trouver 'compileSdk' dans app/build.gradle.kts."
    finish 1
fi
echo "   - Version compileSdk détectée : $COMPILE_SDK"
echo "   - Version buildTools utilisée : $BUILD_TOOLS"


# --- Installation ---
echo "⚙️  Création des dossiers..."
mkdir -p "$SDK_DIR/cmdline-tools"

echo "⚙️  Téléchargement des outils de ligne de commande..."
wget -q --show-progress -O "$CMDLINE_TOOLS_ZIP" "$CMDLINE_TOOLS_URL"

echo "⚙️  Décompression des outils..."
unzip -q "$CMDLINE_TOOLS_ZIP" -d "$SDK_DIR/cmdline-tools"
# Les outils sont dans un dossier "cmdline-tools", on les renomme en "latest"
mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
rm "$CMDLINE_TOOLS_ZIP"

echo "⚙️  Configuration des variables d'environnement..."
export ANDROID_HOME="$(pwd)/$SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "⚙️  Acceptation des licences et installation des paquets..."
set +e
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses > /dev/null
license_status=${PIPESTATUS[1]}
set -e
if [[ ${license_status:-1} -ne 0 ]]; then
    echo "❌ Échec lors de l'acceptation des licences Android (code $license_status)."
    finish "$license_status"
fi

packages=("platform-tools" "platforms;android-$COMPILE_SDK" "build-tools;$BUILD_TOOLS")
if [[ "$BUILD_TOOLS" != "35.0.0" ]]; then
    packages+=("build-tools;35.0.0")
fi
sdkmanager --sdk_root="$ANDROID_HOME" "${packages[@]}"

echo "✅ --- Installation terminée avec succès! ---"
echo "Les variables d'environnement ANDROID_HOME et PATH ont été configurées pour cette session."
echo "Pour les utiliser, vous pouvez sourcer ce script: source setup_android_sdk.sh"
finish 0
