# Signature de l'APK en release

L'APK de debug ne permet pas de mettre à jour proprement : chaque nouvelle
version doit être désinstallée puis réinstallée, ce qui efface favoris,
scripts et réglages. Un APK signé avec **votre** clé se met à jour par-dessus.

La clé n'est jamais dans le dépôt. Elle est créée une fois sur votre appareil,
puis déposée dans les secrets GitHub sous forme encodée.

---

## 1. Créer le trousseau, une seule fois

Dans Termux :

```bash
pkg install -y openjdk-17
cd ~
keytool -genkeypair -v \
  -keystore geckobrowser.jks \
  -alias geckobrowser \
  -keyalg RSA -keysize 4096 \
  -validity 10000
```

Retenez le mot de passe : il n'existe aucun moyen de le récupérer.

> **Sauvegardez `geckobrowser.jks` hors du téléphone.** Perdre cette clé
> signifie ne plus jamais pouvoir mettre à jour l'application installée :
> il faudra la désinstaller et repartir de zéro.
> Ne la mettez pas dans le dépôt — le détecteur de secrets de GitHub
> bloquerait d'ailleurs l'envoi.

## 2. Encoder la clé

```bash
base64 -w 0 ~/geckobrowser.jks > ~/keystore.txt
cat ~/keystore.txt
```

## 3. Déposer les secrets

Sur GitHub, dépôt → Settings → Secrets and variables → Actions →
New repository secret. Quatre entrées :

| Nom                 | Contenu                                  |
|---------------------|------------------------------------------|
| `KEYSTORE_BASE64`   | le contenu de `keystore.txt`             |
| `KEYSTORE_PASSWORD` | mot de passe du trousseau                |
| `KEY_ALIAS`         | `geckobrowser`                           |
| `KEY_PASSWORD`      | mot de passe de la clé                   |

Puis effacez la copie encodée :

```bash
rm ~/keystore.txt
```

## 4. Produire la version signée

Onglet **Actions** → *Build APK* → **Run workflow**, en cochant
« Produire aussi un APK release signé ». L'artefact **GeckoBrowser-release**
apparaît à la fin.

Sans les secrets, l'étape est ignorée sans faire échouer la compilation :
seul l'APK de debug est produit.

---

## Ce que la version release change

- **Signature stable** : les mises à jour s'installent par-dessus, les données
  sont conservées.
- **Minification et réduction des ressources** : le code inutilisé est retiré.
  `proguard-rules.pro` protège ce qui est appelé par réflexion — GeckoView,
  les classes citées dans le manifeste, les délégués, `org.json`.
- **Identifiant distinct** : la version de debug porte le suffixe `.debug`,
  les deux peuvent donc cohabiter sur le même appareil.

## Si la version release plante alors que la debug fonctionne

C'est presque toujours la minification qui a retiré une classe atteinte par
réflexion. Ajoutez une règle `-keep` dans `proguard-rules.pro`, ou mettez
temporairement `minifyEnabled false` pour confirmer la cause. Les traces
restent lisibles : les numéros de ligne sont conservés.
