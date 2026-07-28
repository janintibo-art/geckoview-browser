# GeckoBrowser — étape 1 : identités et conteneurs

Ce paquet ajoute six espaces de navigation réellement isolés par GeckoView :

- Personnel
- Travail
- Banque
- Réseaux sociaux
- Temporaire
- Anonyme

Chaque espace reçoit un `contextId` distinct dans `GeckoSessionSettings`. Les
cookies, connexions, `localStorage` et autres données de sites sont donc partagés
uniquement entre les onglets de la même identité.

## Installation

Depuis la racine du dépôt :

```bash
unzip -o ~/storage/downloads/geckobrowser-containers-overlay.zip
python3 tools/apply_containers.py
python3 tools/check.py
git add .
git commit -m "Ajoute les identites et conteneurs GeckoView"
git push
```

## Dans l'application

Ouvrir **Menu → Identités** pour :

- créer un onglet dans une identité choisie ;
- dupliquer la page actuelle dans une autre identité ;
- choisir l'identité par défaut ;
- effacer uniquement les données d'une identité ;
- afficher le fonctionnement de l'isolation.

La liste des onglets indique désormais l'identité de chaque page. Les onglets
persistants retrouvent leur identité après le redémarrage. Les espaces
Temporaire et Anonyme utilisent le mode privé et ne sont pas restaurés.

## Important

L'identité **Anonyme** isole le stockage local, mais ne masque pas l'adresse IP.
Le routage Tor reste une fonction séparée via Orbot.

Au premier lancement, chaque identité commence avec son propre espace de
cookies. Il est donc normal que certains sites demandent une nouvelle connexion.

## Retour arrière

Le programme d'installation conserve :

`app/src/main/java/com/example/geckobrowser/MainActivity.java.before-containers`

Pour annuler avant d'autres modifications :

```bash
cp app/src/main/java/com/example/geckobrowser/MainActivity.java.before-containers \
   app/src/main/java/com/example/geckobrowser/MainActivity.java
rm app/src/main/java/com/example/geckobrowser/ContainerManager.java
```
