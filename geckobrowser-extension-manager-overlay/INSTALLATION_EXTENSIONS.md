# GeckoBrowser — étape 8 : gestionnaire d'extensions

Cette étape ajoute un gestionnaire natif pour les WebExtensions GeckoView :

- installation depuis un fichier `.xpi` ;
- installation depuis une adresse HTTPS ;
- installation directe en touchant un paquet XPI sur le web ;
- affichage et validation des permissions avant installation ou mise à jour ;
- liste des extensions installées ;
- activation, désactivation et désinstallation ;
- accès en navigation privée réglable extension par extension ;
- recherche de mises à jour ;
- ouverture des options et de la fiche Mozilla Add-ons ;
- actions de navigateur et fenêtres popup des extensions ;
- protection de l'extension intégrée GeckoBlock.

Les extensions externes doivent être signées par Mozilla. Une extension Firefox de
bureau peut aussi être incompatible avec Android ou GeckoView même si son paquet
est correctement signé.

## Installation Termux

```bash
cd ~/geckoview-browser
unzip -o ~/storage/downloads/geckobrowser-extension-manager-overlay.zip
python3 tools/apply_extension_manager.py
python3 tools/check.py
git add .
git commit -m "Ajoute le gestionnaire d extensions"
git push
```

Sauvegarde créée :

```text
app/src/main/java/com/example/geckobrowser/MainActivity.java.before-extension-manager
```

## Utilisation

```text
Menu → Extensions
```
