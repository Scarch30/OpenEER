# Lecteur de fichiers

L’activité `FileViewerActivity` propose une vue immersive avec toolbar partagée pour les blocs `BlockType.FILE`. Elle sélectionne dynamiquement un delegate selon le MIME ou l’extension et expose les actions de partage, renommage et suppression.

## Formats pris en charge

| Format | Délégation | Remarques |
| --- | --- | --- |
| PDF (`application/pdf`) | `PdfViewerFragment` (`com.github.mhiew:android-pdf-viewer`) | Les fichiers `content://` sont copiés en cache avant affichage pour la bibliothèque. |
| Texte (`text/plain`, `text/*`) | `TextViewerFragment` | Lecture streaming (1 MiB max affiché) avec auto-détection UTF-8 → ISO-8859-1. Un toast indique les fichiers tronqués. |
| DOC/DOCX (`application/msword`, `…document`) | `DocumentViewerFragment` + Apache POI (HWPF/XWPF) | Conversion en HTML simplifié affiché dans un `WebView`. |
| ODT (`application/vnd.oasis.opendocument.text`) | `DocumentViewerFragment` + Apache ODF Toolkit | Extraction simple du texte avec stylage basique. |
| RTF (`application/rtf`, `text/rtf`) | `DocumentViewerFragment` | Parsing léger des commandes RTF pour obtenir du texte brut. |
| Image (extensions/mime `image/*`) | Redirigé vers `PhotoViewerActivity` | Évite la duplication et conserve les interactions existantes. |

## Expérience utilisateur

* **Progression / erreurs** : l’activité expose un indicateur de chargement central et un état d’erreur avec actions « Partager » et « Ouvrir ailleurs ».
* **Carte dans la note** : les blocs `FILE` sont rendus sous forme de carte matérialisée avec nom, taille estimée (via `ContentResolver`) et raccourci « Ouvrir ».
* **Partage** : tous les viewers se basent sur `ViewerMediaUtils.resolveShareUri` pour obtenir une URI `content://` partageable.

## Limites et recommandations

* Les fichiers texte supérieurs à 1 MiB sont tronqués pour préserver la mémoire ; l’activité affiche un toast indiquant la taille approximative.
* Les conversions DOC/DOCX/ODT/RTF produisent un HTML minimal sans images ni styles avancés ; en cas d’échec, l’état d’erreur invite l’utilisateur à ouvrir le fichier dans une autre application.
* Pour préserver la taille de l’APK, activer éventuellement le shrinker lors de la compilation `release`. Les règles ProGuard conservent les classes POI/ODFDOM nécessaires.

## Dépendances

* `com.github.mhiew:android-pdf-viewer:3.2.0-beta.3`
* `org.apache.poi:poi`, `poi-ooxml`, `poi-scratchpad`
* `org.apache.odftoolkit:simple-odf`
* `androidx.webkit:webkit` pour `WebViewAssetLoader`

Les règles de minification correspondantes sont ajoutées dans `app/proguard-rules.pro`.
