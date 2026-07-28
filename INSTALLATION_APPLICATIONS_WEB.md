# GeckoBrowser — étape 10 : applications web installables

Cette étape transforme les sites compatibles en petites applications web
accessibles depuis l’écran d’accueil Android.

## Fonctions ajoutées

- détection native des manifestes Web App par GeckoView ;
- installation de la page actuelle depuis `Menu → Applications web` ;
- raccourci épinglé sur l’écran d’accueil Android ;
- catalogue local des applications installées ;
- mode autonome avec une barre compacte dédiée ;
- conservation du mode application après redémarrage du navigateur ;
- sortie automatique du mode autonome lorsqu’un lien quitte la portée du site ;
- ouverture possible dans le navigateur normal ;
- renommage, réparation du raccourci et retrait du catalogue ;
- création d’un raccourci simple lorsqu’un site ne fournit pas de manifeste ;
- aucune installation depuis un onglet privé ;
- icônes générées localement, sans contacter un serveur d’icônes.

## Installation avec Termux

```bash
cd ~/geckoview-browser
unzip -o ~/storage/downloads/geckobrowser-webapps-overlay.zip
python3 tools/apply_web_apps.py
python3 tools/check.py
git add .
git commit -m "Ajoute les applications web installables"
git push
```

Le script conserve automatiquement la version précédente de l’activité :

```text
app/src/main/java/com/example/geckobrowser/MainActivity.java.before-web-apps
```

## Utilisation

Ouvrez un site, puis :

```text
Menu → Applications web → Installer la page actuelle
```

Lorsqu’un manifeste valide est disponible, GeckoBrowser reprend son nom, son
adresse de départ, sa portée, son mode d’affichage et ses couleurs. Sans
manifeste, il propose tout de même un raccourci web simple.

Android affiche sa propre confirmation avant d’ajouter le raccourci à l’écran
d’accueil. Retirer une application du catalogue GeckoBrowser ne supprime pas de
force un raccourci déjà épinglé : celui-ci reste supprimable depuis le lanceur.
