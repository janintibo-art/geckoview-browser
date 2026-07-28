# GeckoBrowser — étape 5 : groupes, aperçus et mise en veille

Ce paquet ajoute un véritable espace de travail pour les onglets :

- aperçu visuel de la page ;
- groupes prédéfinis et groupes personnalisés ;
- onglets épinglés ;
- mise en veille manuelle ;
- mise en veille automatique après 5, 15, 30, 60 ou 120 minutes ;
- restauration du groupe, de l'épinglage, de l'activité et de l'état de veille ;
- aucun aperçu enregistré pour les onglets privés ;
- fermeture de tous les autres onglets sans fermer les onglets épinglés.

## Installation avec Termux

```bash
cd ~/geckoview-browser
unzip -o ~/storage/downloads/geckobrowser-tab-workspace-overlay.zip
python3 tools/apply_tab_workspace.py
python3 tools/check.py
git add .
git commit -m "Ajoute groupes apercus et veille des onglets"
git push
```

Le script conserve automatiquement une sauvegarde :

```text
app/src/main/java/com/example/geckobrowser/MainActivity.java.before-tab-workspace
```

## Utilisation

Ouvrez `Menu → Onglets` puis touchez un onglet pour afficher son aperçu et ses actions.

Les symboles utilisés dans la liste sont :

- `●` : onglet actif ;
- `★` : onglet épinglé ;
- `◌` : onglet en veille ;
- `◐` : onglet privé ;
- `○` : onglet ordinaire.

La mise en veille ferme la session Gecko de l'onglet inactif mais conserve son adresse et son état de session. L'onglet se réveille lors de sa prochaine ouverture.
