# GeckoBrowser — barre intelligente de sélection

Cette étape ajoute des actions directement dans la barre flottante de sélection
de GeckoView :

- Traduire la sélection avec le traducteur Lingva déjà intégré ;
- rechercher le texte avec le moteur choisi dans GeckoBrowser ;
- lire le texte avec la synthèse vocale Android ;
- partager le texte ;
- enregistrer une citation avec son titre, son adresse et sa date ;
- copier une citation au format Markdown.

Les actions personnalisées ne sont jamais affichées dans un champ de mot de
passe. Les fonctions Android habituelles — copier, couper, coller et tout
sélectionner — restent disponibles.

## Installation Termux

```bash
cd ~/geckoview-browser
unzip -o ~/storage/downloads/geckobrowser-smart-selection-overlay.zip
python3 tools/apply_smart_selection.py
python3 tools/check.py
git add .
git commit -m "Ajoute la barre intelligente de selection"
git push
```

Une sauvegarde est créée automatiquement avant la modification :

```text
app/src/main/java/com/example/geckobrowser/MainActivity.java.before-smart-selection
```

Après installation, sélectionnez du texte par appui long. Les nouvelles actions
apparaissent dans la barre flottante ou dans son menu de débordement. Les textes
enregistrés sont accessibles par `Menu > Citations`.
