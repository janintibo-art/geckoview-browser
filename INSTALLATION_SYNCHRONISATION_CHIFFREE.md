# GeckoBrowser — étape 12 : synchronisation chiffrée

Cette étape ajoute une synchronisation native et chiffrée via le sélecteur de
fichiers Android. Le fichier `.gbsync` peut être placé dans le stockage local ou
chez un fournisseur compatible avec Android Documents, par exemple Nextcloud,
Google Drive ou Dropbox.

## Contenu synchronisé

- favoris et catégories ;
- citations enregistrées ;
- réglages compatibles du navigateur ;
- catalogue des applications web ;
- onglets normaux et état de session.

Les onglets privés, les téléchargements, le cache et le coffre de mots de passe
lié à Android Keystore sont exclus. Le coffre conserve son export portable
`.gbvault` séparé.

## Sécurité

- AES-256-GCM avec authentification du paquet ;
- clé dérivée par PBKDF2-HMAC-SHA256, 600 000 itérations ;
- sel et vecteur d'initialisation aléatoires à chaque écriture ;
- phrase facultativement mémorisée avec une clé non exportable Android Keystore ;
- détection d'un fichier distant plus récent avant l'envoi automatique.

## Installation Termux

```bash
cd ~/geckoview-browser
unzip -o ~/storage/downloads/geckobrowser-encrypted-sync-overlay.zip
python3 tools/apply_encrypted_sync.py
python3 tools/check.py
git add .
git commit -m "Ajoute la synchronisation chiffree"
git push
```

Sauvegarde créée :

```text
app/src/main/java/com/example/geckobrowser/MainActivity.java.before-encrypted-sync
```

## Utilisation

```text
Menu → Synchronisation chiffrée
```

Créez ou reliez un fichier `.gbsync`, choisissez une phrase secrète identique
sur chaque appareil, puis utilisez « Envoyer cet appareil » ou « Recevoir et
fusionner ».

L'ancien outil GitHub des scripts et styles reste accessible depuis le nouveau
menu, car le stockage interne de la WebExtension est séparé du stockage natif.
