# GeckoBrowser — restauration complète des sessions

Ce paquet remplace directement `MainActivity.java` et ajoute `SessionStore.java`.
Aucun script d'installation n'est nécessaire.

Fonctions ajoutées :
- historique précédent/suivant restauré ;
- position de défilement restaurée ;
- niveau de zoom restauré ;
- données de formulaires restaurées lorsque Gecko les fournit ;
- onglet actif restauré ;
- ouverture paresseuse des onglets pour conserver un démarrage rapide ;
- onglets privés toujours exclus ;
- migration automatique de l'ancien format URL + titre ;
- fichier atomique privé `session-v2.json` pour éviter les états partiellement écrits.

Installation Termux :

```bash
cd ~/geckoview-browser
# Nettoyage sans danger des restes de l'etape Conteneurs abandonnee
rm -f app/src/main/java/com/example/geckobrowser/ContainerManager.java
rm -f tools/apply_containers.py
unzip -o ~/storage/downloads/geckobrowser-session-complete-overlay.zip
python3 tools/check.py
git add .
git commit -m "Ajoute la restauration complete des sessions"
git push
```
