# OpenEER

## Route Debug Overlay
- Disponible en build DEBUG via le menu overflow « Overlay debug itinéraire ».
- Le switch active/désactive l’overlay à la volée pour afficher les courbes et sliders.
- Les sliders modifient les seuils en temps réel ; les valeurs sont conservées localement dans les préférences.

## Initialisation automatique du SDK Android

Le script [`auto_setup_android_sdk.sh`](./auto_setup_android_sdk.sh) automatise le téléchargement du SDK Android
dans l'environnement de travail temporaire et configure les variables `ANDROID_HOME` et `PATH` pour la session
courante. Pour qu'il soit exécuté à chaque ouverture de shell, ajoutez la ligne suivante à la fin de votre
`~/.bashrc` (ou `~/.zshrc`) :

```bash
source /workspace/OpenEER/auto_setup_android_sdk.sh
```

Lors de la première exécution, le SDK est téléchargé dans `android_sdk/`. Les sessions suivantes réutilisent le
cache existant et reconfigurent simplement l'environnement.
