#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="$SCRIPT_DIR/android_sdk"

ensure_sdk() {
    if [ ! -d "$SDK_DIR" ]; then
        echo "📦 Installation du SDK Android (absent de cette session)..."
        (cd "$SCRIPT_DIR" && bash ./setup_android_sdk.sh)
    else
        echo "✅ SDK Android déjà présent : $SDK_DIR"
    fi
}

configure_env() {
    export ANDROID_HOME="$SDK_DIR"
    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
}

ensure_sdk
configure_env

echo "✅ Environnement Android prêt."
echo "   ANDROID_HOME=$ANDROID_HOME"
