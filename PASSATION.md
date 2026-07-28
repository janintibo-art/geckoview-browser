# GeckoBrowser — passation de projet

Colle ce document en premier message d'une nouvelle conversation avec Claude
pour reprendre le développement sans perte de contexte.

---

## Ce que c'est

Un navigateur Android complet basé sur **GeckoView** (moteur de Firefox),
développé de zéro dans Termux, compilé via **GitHub Actions**, signé en
release. Dépôt : `github.com/janintibo-art/geckoview-browser`.

Le projet a démarré comme un simple navigateur avec bloqueur de pub, et s'est
enrichi progressivement jusqu'à couvrir : filtrage éditorial, métamoteur,
scripts utilisateur, styles CSS par site, Tor, onglets, historique, file de
lecture hors ligne, flux maison, détecteur de procédés trompeurs, bilan de
concentration des médias, et plus. Voir la liste complète plus bas.

## Comment travailler dessus

- Le code source complet est sur GitHub, branche `main`.
- Le développement se fait par **overlays** : Claude écrit les fichiers
  modifiés dans un dossier de travail, les zippe, l'utilisateur les
  télécharge, les dézippe **par-dessus** le projet existant dans Termux
  (`unzip -o`), puis commit/push. Ça n'écrase jamais tout le dépôt.
- **`tools/check.py`** est un contrôle statique à lancer avant tout push : il
  détecte les collisions de portée JS, les méthodes Java citées mais
  absentes, les ressources manquantes, le XML mal formé, les scripts en
  ligne bloqués par la CSP de l'extension. Il a été construit après plusieurs
  incidents réels (voir plus bas) et testé contre eux. **Toujours l'exécuter
  après une modification, avant de zipper.**
- Le workflow GitHub Actions (`.github/workflows/build.yml`) lance ce
  contrôle, compile un APK debug à chaque push, et un APK release signé sur
  déclenchement manuel (case à cocher).

## Architecture

- **Application Android** (`app/src/main/java/com/example/geckobrowser/`) :
  `MainActivity` est le cœur — barre d'adresse, menu, onglets, widgets, tout
  y converge. Les classes annexes (`Privacy`, `TorSupport`, `Downloads`,
  `Prompts`, `Permissions`, `Menus`, `Shortcuts`, `AudioExtractor`,
  `SearchWidget`, `StatsWidget`) gèrent chacune un domaine précis.
- **Extension WebExtension embarquée** (`app/src/main/assets/adblock/`) :
  c'est là que vit presque toute la logique — blocage, filtres, moteur de
  recherche, scripts utilisateur, etc. Chargée via
  `GeckoRuntime.getWebExtensionController().ensureBuiltIn(...)`.
- **Communication app ↔ extension** : GeckoView n'a pas d'API `tabs`, donc
  tout passe par un port natif (`browser.runtime.connectNative("browser")`)
  côté extension, relié à un `WebExtension.Port` côté Java. Le menu Android
  envoie des commandes (`sendCommand("nomDeCommande")`) qui sont écrites dans
  `browser.storage.local` sous la clé `pageCommand`, que **tous** les
  scripts de contenu observent via `storage.onChanged`.
- **`shared.js`** est un script de contenu chargé en premier, qui expose un
  objet global `GB` avec du code mutualisé : détection de page suivante
  (pagination), sélecteurs de bandeaux de consentement, extraction du texte
  principal d'une page, pointeur d'élément générique (`GB.pick()`). **Toute
  nouvelle fonctionnalité qui a besoin d'un de ces mécanismes doit
  réutiliser `GB.*`, pas le réécrire.**

## Fonctionnalités livrées (ordre chronologique approximatif)

1. Navigateur de base GeckoView (barre d'adresse, session, historique)
2. Bloqueur de pub (extension + listes AdAway/Peter Lowe + filtrage cosmétique)
3. Filtres éditoriaux par catégorie (Bolloré, extrême droite, etc. —
   listes dans `assets/adblock/lists/*.txt`, éditables directement)
4. Métamoteur de recherche (agrège DuckDuckGo, Mojeek, Brave, Marginalia,
   SearXNG optionnel) avec page d'accueil de marque
5. Anti-cookies (refus automatique des CMP + blocage cookies tiers)
6. Gestionnaire de scripts utilisateur (façon Tampermonkey, API `GM_*`)
7. Menu restructuré avec sous-menus (`Menus.java`, thème sombre)
8. Choix du moteur de recherche par défaut (barre d'adresse) distinct des
   sources du métamoteur
9. Redirecteur de façades (YouTube→Invidious, Twitter→Nitter, etc. —
   YouTube désactivé par défaut, bloqué par la plateforme)
10. Styles CSS par site + pointeur d'élément pour masquer manuellement
11. Défilement infini (pagination automatique)
12. Analyseur de page à 6 onglets : Ressources, Code, Console JS, Réseau,
    Extraire (scraper avec export CSV/JSON), Infos
13. Téléchargement groupé de ressources + extraction audio (sans réencodage,
    .m4a/.ogg, pas de vrai MP3 car Android n'a pas d'encodeur)
14. Routage Tor via Orbot (accès au réseau, PAS l'anonymat de Tor Browser —
    nuance importante documentée dans l'aide)
15. Niveaux de confidentialité (Standard/Renforcé/Strict) + navigation privée
    + DNS chiffré + effacement des données
16. Widgets d'écran d'accueil (recherche + compteur de blocage)
17. Identité d'appareil (neuf profils, agent + plateforme + points tactiles
    cohérents entre eux)
18. Diagnostic d'empreinte (canvas, WebGL, audio, polices, WebRTC —
    s'exécute dans le contexte d'une page réelle, pas dans l'extension)
19. Sauvegarde de page en fichier HTML autonome (ressources incorporées)
20. Synchronisation via dépôt GitHub (scripts, styles, filtres, favoris)
21. Onglets complets + restauration de session (onglets privés exclus)
22. Signature APK release (voir `tools/SIGNING.md`)
23. Surveillance de pages (pointeur → vérification périodique → notification)
24. Rapport « qui parle à qui » (tiers contactés, catégorisés, avec propriétaire)
25. Comparaison de versions d'une page (diff sur le texte principal)
26. File de lecture hors ligne (article nettoyé, images incorporées)
27. Flux maison (RSS fabriqué depuis une page sans flux natif)
28. Historique avec recherche plein texte (indexation du texte désactivée
    par défaut — choix de conception délibéré, voir section suivante)
29. Détecteur de procédés trompeurs (dark patterns : precoché, faux compte à
    rebours, fausse rareté, faux avis, refus culpabilisant, publi-rédactionnel
    caché, bandeau sans refus visible, cibles tactiles trop petites)
30. Raccourcis sous la barre de recherche (page d'accueil) + raccourcis sur
    le bureau Android (`Shortcuts.java`, icônes dessinées localement)
31. Alerte "mouchards" à l'ouverture d'un site (notification discrète,
    auto-effacement, mute par site)
32. Bilan de lecture (concentration des lectures par groupe propriétaire,
    croise historique + `publishers.js`)
33. "Ce sujet vu ailleurs" (relance une recherche en excluant le groupe
    propriétaire du site courant)
34. Corbeille (onglets fermés / favoris supprimés, restaurables)
35. Tutoriel intégré par onglets avec recherche plein texte (`help.html` /
    `help.js`), tenu à jour à chaque fonctionnalité
36. Recherche dans la page (barre native sous la barre d'adresse, moteur
    `SessionFinder` de Gecko : surlignage, compteur, précédent/suivant ;
    le bouton retour la ferme avant de remonter l'historique)

## Choix de conception à connaître (évite de les redécouvrir)

- **Identité, pas anonymat** : le mode Tor et le profil d'appareil sont
  présentés honnêtement comme des mesures anti-pistage, pas comme un
  anonymat réel. Le diagnostic d'empreinte le rappelle.
- **Historique texte désactivé par défaut** : indexer ce qu'on lit est
  puissant mais sensible, donc c'est un choix explicite, pas une case cochée
  d'office.
- **Icônes de raccourcis dessinées localement** (pastille + initiale), jamais
  de favicon récupérée en ligne : ça éviterait de signaler au site la
  création du raccourci avant même la première visite.
- **Aucune notification de flux/surveillance par défaut** : un relevé de
  titres deviendrait vite envahissant. L'utilisateur active au cas par cas.
- **Détecteur de dark patterns = indices, pas verdicts** : la formulation
  insiste toujours sur le fait qu'un signal peut être un faux positif.
- **Une commande de menu ne vise que l'onglet affiché** : `pageCommand` est
  diffusé par le stockage à toutes les pages ouvertes. Chaque écouteur doit
  donc se garder avec `GB.foreground()`, et côté Java `applyTabActivity()`
  (appelé par `selectTab()` et `setupSession()`) marque un seul onglet actif
  via `setActive()`/`setFocused()` — c'est ce qui rend
  `document.visibilityState` fiable dans les scripts de contenu. Tout nouvel
  écouteur de `pageCommand` doit reprendre cette garde.
- **Restauration paresseuse des onglets** : `restoreTabs()` crée les onglets
  sans ouvrir leur session (`Tab.pending`) ; `selectTab()` ouvre et charge à
  la première sélection. Ne jamais réintroduire un chargement immédiat de
  tous les onglets au démarrage. `applyTabActivity()` ignore les sessions
  non ouvertes.
- **Métamoteur progressif** : `run()` (search.js) rend les résultats dès le
  premier moteur qui répond et re-fusionne à chaque arrivée, avec un garde
  `runSeq` contre les requêtes croisées. Un cache de reprise (`searchCache`,
  10 min, volontairement absent de la synchronisation) rend le retour
  arrière instantané ; le pied de page propose « actualiser ».
- **Palette canonique = `search.css`** (`--bg #14161a`, `--panel #1c1f26`) :
  le côté Android y est aligné (layout, `brand_bg`, `setClearColor`,
  pref Gecko `browser.display.background_color`). Toute nouvelle couleur de
  fond doit reprendre ces valeurs. Les icônes de la barre sont des
  `VectorDrawable` locaux (`ic_menu_dots`, `ic_go_arrow`), jamais des
  icônes système.
- **Vérification systématique après édition d'un bloc de code existant** :
  remplacer un bloc entre deux repères textuels a fait disparaître des
  méthodes voisines à plusieurs reprises. Toujours relire ce qui reste
  autour, et faire tourner `tools/check.py`.

## Incidents réels rencontrés (pour ne pas les reproduire)

- **Collision de portée JS** : deux scripts chargés dans la même page HTML
  déclaraient tous deux `const ENGINES`, ce qui invalidait silencieusement
  le second fichier entier. → toujours encapsuler dans une IIFE
  `(function(){...})()` ou vérifier l'absence de collision avec
  `tools/check.py`.
- **Suppression accidentelle de méthodes** : remplacer un bloc de code entre
  deux repères a emporté des méthodes utilitaires situées entre les deux à
  plusieurs reprises (une fois 4 méthodes, une fois ~15). → toujours relire
  le fichier après une édition de bloc large, ou réécrire en un seul bloc
  complet plutôt que par petites retouches successives.
- **`git init` au mauvais endroit** : lancé dans `~` au lieu du dossier
  projet, a bien failli committer tout le home (avec tokens dans
  `.bash_history` et `.config/gh/hosts.yml`). GitHub a bloqué le push grâce
  au détecteur de secrets. → toujours vérifier `pwd` avant `git init`.
- **`apply()` vs `commit()`** : `SharedPreferences.edit().apply()` est
  asynchrone. Un redémarrage immédiat (`Runtime.getRuntime().exit(0)`) peut
  tuer le processus avant l'écriture. → utiliser `.commit()` avant tout
  redémarrage volontaire.
- **`setClearColor` sans effet** : appelé juste après `session.open()`, avant
  que la session soit attachée à la vue via `setSession()`. Le compositeur
  n'existe qu'après. → toujours vérifier l'ordre des opérations GeckoView.
- **Mot de passe de trousseau erroné lors de la signature** : plusieurs
  allers-retours car le mot de passe tapé dans le terminal ne correspondait
  pas à celui réellement stocké dans les secrets GitHub (fractionnement des
  commandes en plusieurs étapes, oubli d'une commande). → toujours faire la
  création du trousseau + dépôt des 4 secrets en **un seul bloc de commandes
  enchaînées par `&&`**, avec le mot de passe dans une variable utilisée
  partout, jamais retapé.

## Pour la prochaine session

Idées déjà évoquées mais pas faites : migration des anciens pointeurs vers
`GB.pick()` (dette technique connue), détecteur de mouchards *dans* le
rapport réseau enrichi. Demander à
l'utilisateur ce qu'il veut avant de partir dans une direction.

Toujours commencer une nouvelle demande de fonctionnalité par : lire les
fichiers concernés existants, vérifier s'il y a déjà du code réutilisable
dans `shared.js` ou `publishers.js`, écrire, faire tourner
`tools/check.py`, zipper, présenter avec `present_files`.
