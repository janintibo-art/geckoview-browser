# GeckoBrowser — cockpit de confidentialité

Ce paquet ajoute un tableau de bord natif par onglet, alimenté directement par
les événements de blocage et de sécurité de GeckoView.

## Fonctions

- appui sur le bouclier : ouverture du cockpit ;
- appui long sur le bouclier : activation ou désactivation rapide du bloqueur ;
- compteur natif par onglet des ressources bloquées et autorisées ;
- catégories : publicité, mesure d'audience, réseaux sociaux, empreinte,
  cryptominage, cookies/stockage et contenu dangereux ;
- liste des domaines concernés ;
- état HTTPS, exception de certificat et contenu mixte ;
- résumé des réglages actifs : profil, navigation privée, DNS chiffré, Tor et
  alerte mouchards ;
- copie du rapport ;
- remise à zéro des compteurs lors du rechargement ;
- effacement des cookies, stockages, caches et permissions de l'hôte courant.

L'indice A–E est seulement un repère local. Il ne constitue pas un audit de
sécurité du site.

## Installation avec Termux

```bash
cd ~/geckoview-browser
unzip -o ~/storage/downloads/geckobrowser-privacy-cockpit-overlay.zip
python3 tools/check.py
git add .
git commit -m "Ajoute le cockpit de confidentialite"
git push
```

## Utilisation

Ouvrez une page web puis touchez le bouclier. Le cockpit est aussi accessible
dans **Menu → Confidentialité → Cockpit de confidentialité**.

Le paquet est basé sur le commit :
`ba44867eb4f836303f08f6b22cd916c9cfebc6c0`
(restauration complète des sessions).
