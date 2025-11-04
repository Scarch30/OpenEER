#!/bin/bash
# Script pour télécharger et configurer le SDK Android requis pour ce projet.

# --- Variables ---
# Met à jour ce lien si nécessaire depuis https://developer.android.com/studio#command-tools
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
SDK_DIR="android_sdk"
SDKMANAGER_PATH="${SDK_DIR}/cmdline-tools/latest/bin/sdkmanager"

# Paquets à installer (basé sur l'analyse de build.gradle.kts et .github/workflows/android-build.yml)
SDK_PACKAGES=(
    "platform-tools"
    "platforms;android-35"
    "platforms;android-34"
    "build-tools;34.0.0"
)

# --- Fonctions ---
log() {
    echo "[SETUP_SDK] -> $1"
}

# --- Début du script ---
set -e # Quitte immédiatement si une commande échoue

log "Début de l'installation du SDK Android..."

# 1. Nettoyage et création du répertoire du SDK
if [ -d "$SDK_DIR" ]; then
    log "Le répertoire '$SDK_DIR' existe déjà. Nettoyage..."
    rm -rf "$SDK_DIR"
fi
mkdir -p "$SDK_DIR/cmdline-tools"
log "Répertoire du SDK créé : $SDK_DIR"

# 2. Téléchargement et décompression des outils de ligne de commande
log "Téléchargement des outils de ligne de commande..."
wget -q -O cmdline-tools.zip "$CMDLINE_TOOLS_URL"
log "Décompression des outils..."
unzip -q -d "$SDK_DIR/cmdline-tools" cmdline-tools.zip
# Les outils sont dans un sous-dossier, on les déplace vers 'latest'
mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
rm cmdline-tools.zip
log "Outils installés dans $SDK_DIR/cmdline-tools/latest"

# 3. Installation des paquets du SDK
log "Acceptation des licences SDK..."
yes | "$SDKMANAGER_PATH" --licenses > /dev/null

log "Installation des paquets requis : ${SDK_PACKAGES[*]}"
for package in "${SDK_PACKAGES[@]}"; do
    log "Installation de '$package'..."
    "$SDKMANAGER_PATH" --install "$package" > /dev/null
done
log "Tous les paquets ont été installés."

# 4. Création du fichier local.properties
log "Création du fichier 'local.properties'..."
echo "sdk.dir=$(pwd)/$SDK_DIR" > local.properties
log "Fichier 'local.properties' créé avec succès."

# 5. Définition de ANDROID_HOME (pour la session actuelle)
export ANDROID_HOME="$(pwd)/$SDK_DIR"
log "Variable d'environnement ANDROID_HOME définie pour la session actuelle."
log "Pour la rendre permanente, ajoutez 'export ANDROID_HOME=$(pwd)/$SDK_DIR' à votre ~/.bashrc ou ~/.zshrc"

log "--- ✅ Installation terminée avec succès ! ---"
log "Vous pouvez maintenant lancer les commandes Gradle comme './gradlew assembleDebug'."
