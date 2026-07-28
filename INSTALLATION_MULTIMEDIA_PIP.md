# GeckoBrowser — étape 6 : multimédia, plein écran et PiP

Fonctions ajoutées :

- commandes natives lecture/pause, avance, recul, piste précédente/suivante et arrêt ;
- notification multimédia Android et commandes de casque/écran verrouillé ;
- menu `GeckoBrowser → Multimédia` ;
- plein écran web correct, avec masquage de la barre du navigateur ;
- image dans l’image manuelle ;
- image dans l’image automatique lorsque l’application est quittée pendant une lecture ;
- les onglets qui jouent un média ne sont pas mis en veille automatiquement ;
- préparation du plus grand élément vidéo avant le passage en PiP.

## Installation Termux

```bash
cd ~/geckoview-browser
unzip -o ~/storage/downloads/geckobrowser-media-overlay.zip
python3 tools/apply_media_hub.py
python3 tools/check.py
git add .
git commit -m "Ajoute controles multimedia plein ecran et PiP"
git push
```

Le script conserve une sauvegarde :

```text
app/src/main/java/com/example/geckobrowser/MainActivity.java.before-media-hub
```

## Utilisation

Lancez une vidéo ou un morceau, puis ouvrez :

```text
Menu → Multimédia
```

La notification Android apparaît lorsqu’une session multimédia web devient active. Le PiP automatique peut être désactivé depuis le même menu.
