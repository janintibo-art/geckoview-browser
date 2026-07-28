# GeckoBrowser — étape 7 : centre de téléchargements

Cette étape ajoute un véritable gestionnaire de téléchargements :

- téléchargements HTTP confiés au `DownloadManager` Android ;
- poursuite en arrière-plan avec notification système ;
- progression, taille reçue et état dans `Menu → Téléchargements` ;
- restriction facultative au Wi-Fi ;
- ouverture, partage, annulation et nouvelle tentative ;
- historique unifié pour les fichiers reçus directement par Gecko ;
- conservation du chemin SOCKS existant lorsque Tor/Orbot est actif ;
- accès direct au dossier public `Téléchargements/GeckoBrowser`.

## Installation avec Termux

```bash
cd ~/geckoview-browser
unzip -o ~/storage/downloads/geckobrowser-download-center-overlay.zip
python3 tools/apply_download_center.py
python3 tools/check.py
git add .
git commit -m "Ajoute le centre de telechargements"
git push
```

Le script conserve automatiquement :

```text
app/src/main/java/com/example/geckobrowser/MainActivity.java.before-download-center
app/src/main/java/com/example/geckobrowser/Downloads.java.before-download-center
app/src/main/AndroidManifest.xml.before-download-center
```

## Utilisation

Ouvrez :

```text
Menu → Téléchargements
```

Le réglage « Wi-Fi uniquement » s'applique aux prochains téléchargements.
Les fichiers déjà lancés gardent la règle réseau choisie au moment de leur ajout.

Les téléchargements via Tor restent réalisés par le code interne du navigateur,
car le gestionnaire système Android ne reprend pas automatiquement le proxy SOCKS
d'Orbot.
