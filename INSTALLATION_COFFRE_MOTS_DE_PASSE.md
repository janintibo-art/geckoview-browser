# GeckoBrowser — étape 9 : coffre chiffré de mots de passe

Cette étape ajoute un gestionnaire local de mots de passe relié au remplissage
automatique natif de GeckoView.

## Fonctions ajoutées

- proposition d’enregistrement et de mise à jour après une connexion ;
- sélection du compte lorsqu’un site possède plusieurs identifiants ;
- remplissage automatique désactivable ;
- aucun nouvel identifiant enregistré depuis un onglet privé ;
- gestion manuelle des comptes depuis `Menu → Mots de passe` ;
- ajout, modification, suppression, affichage et copie temporaire ;
- générateur de mots de passe robustes ;
- liste « ne jamais enregistrer pour ce site » ;
- chiffrement local AES-256-GCM avec une clé Android Keystore non exportable ;
- confirmation par le verrouillage système avant l’ouverture du coffre ;
- presse-papiers marqué sensible et effacé après 30 secondes ;
- export et restauration dans un fichier `.gbvault` chiffré par phrase secrète ;
- exclusion du coffre lié à l’appareil des sauvegardes Android.

Le remplissage peut rester disponible en navigation privée, mais GeckoBrowser ne
propose jamais d’y enregistrer ou d’y mettre à jour un mot de passe.

## Installation Termux

```bash
cd ~/geckoview-browser
unzip -o ~/storage/downloads/geckobrowser-password-vault-overlay.zip
python3 tools/apply_password_vault.py
python3 tools/check.py
git add .
git commit -m "Ajoute le coffre chiffre de mots de passe"
git push
```

Le script conserve automatiquement :

```text
app/src/main/java/com/example/geckobrowser/MainActivity.java.before-password-vault
app/src/main/AndroidManifest.xml.before-password-vault
```

## Utilisation

```text
Menu → Mots de passe
```

La sauvegarde portable se trouve dans `Téléchargements/GeckoBrowser`. Elle ne
peut être restaurée sans la phrase secrète choisie lors de l’export.

Le fichier interne `password-vault.bin` n’est pas transféré par la sauvegarde
Android, car sa clé Android Keystore reste liée à l’installation et à l’appareil.
