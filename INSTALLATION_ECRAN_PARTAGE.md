# GeckoBrowser — étape 11 : écran partagé

Cette étape affiche deux onglets simultanément dans la même activité GeckoBrowser.

## Fonctions ajoutées

- deux pages GeckoView réellement interactives à l'écran ;
- disposition automatique : côte à côte en paysage, haut/bas en portrait ;
- dispositions forcées côte à côte ou haut/bas ;
- séparation redimensionnable au doigt ;
- proportions rapides 30/70, 40/60, 50/50, 60/40 et 70/30 ;
- toucher un volet lui donne le focus ;
- la barre d'adresse, la recherche et les menus agissent sur le volet entouré ;
- permutation des deux volets ;
- remplacement du volet actif par n'importe quel onglet ouvert ;
- création immédiate d'un nouvel onglet dans le second volet ;
- onglets normaux et privés utilisables ensemble, sans persistance des onglets privés ;
- les deux sessions visibles restent actives pour éviter de figer la page secondaire ;
- sortie automatique de l'écran partagé avant le plein écran vidéo, le PiP ou une application web autonome ;
- fermeture d'un volet sans fermer silencieusement l'autre onglet.

Le mode partagé n'est pas restauré automatiquement après un redémarrage. Les onglets,
leur historique et leur état restent néanmoins enregistrés par le système de session
existant.

## Installation avec Termux

```bash
cd ~/geckoview-browser
unzip -o ~/storage/downloads/geckobrowser-split-screen-overlay.zip
python3 tools/apply_split_screen.py
python3 tools/check.py
git add .
git commit -m "Ajoute l ecran partage"
git push
```

Sauvegarde créée avant modification :

```text
app/src/main/java/com/example/geckobrowser/MainActivity.java.before-split-screen
```

## Utilisation

```text
Menu → Écran partagé
```

Choisissez un second onglet ou créez un nouveau volet. Le contour coloré indique
le volet qui reçoit les commandes. Faites glisser la barre centrale pour modifier
la taille des deux pages.
