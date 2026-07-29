# GeckoBrowser — correction du contrôle statique

Le contrôle statique interprétait `new WebAppManager.App()` comme un appel à une
méthode statique `WebAppManager.App()`. Or `App` est une classe imbriquée publique.
La compilation Android n'avait donc pas encore commencé : le workflow s'arrêtait
sur un faux positif de `tools/check.py`.

## Installation Termux

```bash
cd ~/geckoview-browser
unzip -o ~/storage/downloads/geckobrowser-static-check-fix.zip
python3 tools/fix_static_check_nested_types.py
python3 tools/check.py

git add tools/check.py tools/fix_static_check_nested_types.py INSTALLATION_CORRECTION_CONTROLE_STATIQUE.md
git commit -m "Corrige le controle statique des classes imbriquees"
git push
```

Sauvegarde créée :

```text
tools/check.py.before-nested-types-fix
```
