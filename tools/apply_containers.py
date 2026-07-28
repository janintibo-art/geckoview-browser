#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ajoute les identites/conteneurs a MainActivity.java.

Le script est volontairement conservateur : chaque modification attend un bloc
precis du projet actuel. En cas d'ecart, il s'arrete sans ecraser le fichier.
"""

from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/example/geckobrowser/MainActivity.java"
BACKUP = MAIN.with_suffix(".java.before-containers")


def fail(message: str) -> None:
    print(f"ERREUR: {message}", file=sys.stderr)
    sys.exit(1)


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        fail(f"bloc '{label}' attendu une fois, trouve {count} fois. "
             "Le projet a probablement change; aucun fichier n'a ete ecrit.")
    return source.replace(old, new, 1)


if not MAIN.exists():
    fail(f"fichier introuvable: {MAIN}")

src = MAIN.read_text(encoding="utf-8")
if "ContainerManager.contextId(" in src:
    print("Les identites sont deja installees; aucune modification necessaire.")
    sys.exit(0)

original = src

src = replace_once(src,
'''        boolean priv;
        /** Adresse a charger a la premiere selection (restauration paresseuse). */''',
'''        boolean priv;
        /** Identite GeckoView : cookies et stockage isoles des autres identites. */
        String containerId = ContainerManager.DEFAULT_ID;
        /** Adresse a charger a la premiere selection (restauration paresseuse). */''',
"champ containerId")

src = replace_once(src,
'''    /** Cree un onglet, l'ajoute a la liste et l'affiche. */
    private void setupSession(boolean priv, String target) {
        setupSession(priv, target, false);
    }

    /**
     * @param lazy vrai pour la restauration paresseuse : l'onglet est cree
     *             mais la session n'est ni ouverte ni chargee avant sa
     *             premiere selection. Ouvrir et charger une dizaine de pages
     *             simultanement au demarrage rendait le lancement poussif.
     */
    private void setupSession(boolean priv, String target, boolean lazy) {
        privateMode = priv;

        int pi = profileIndex();
        if (pi > 0 && pi < PROFILES.length) desktopMode = "1".equals(PROFILES[pi][4]);

        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .usePrivateMode(priv)
                .userAgentMode(desktopMode
                        ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                        : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(desktopMode
                        ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                        : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build();

        final Tab tab = new Tab();
        tab.priv = priv;''',
'''    /** Cree un onglet, l'ajoute a la liste et l'affiche. */
    private void setupSession(boolean priv, String target) {
        String identity = priv
                ? ContainerManager.TEMPORARY_ID
                : ContainerManager.defaultId(this);
        setupSession(identity, priv, target, false);
    }

    private void setupSession(boolean priv, String target, boolean lazy) {
        String identity = priv
                ? ContainerManager.TEMPORARY_ID
                : ContainerManager.defaultId(this);
        setupSession(identity, priv, target, lazy);
    }

    private void setupSession(String containerId, String target) {
        setupSession(containerId, ContainerManager.isPrivate(containerId), target, false);
    }

    /**
     * @param lazy vrai pour la restauration paresseuse : l'onglet est cree
     *             mais la session n'est ni ouverte ni chargee avant sa
     *             premiere selection. Ouvrir et charger une dizaine de pages
     *             simultanement au demarrage rendait le lancement poussif.
     */
    private void setupSession(String containerId, boolean requestedPrivate,
                              String target, boolean lazy) {
        final String identity = ContainerManager.normalize(containerId);
        final boolean actualPrivate = requestedPrivate || ContainerManager.isPrivate(identity);
        privateMode = actualPrivate;

        int pi = profileIndex();
        if (pi > 0 && pi < PROFILES.length) desktopMode = "1".equals(PROFILES[pi][4]);

        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .contextId(ContainerManager.contextId(identity))
                .usePrivateMode(actualPrivate)
                .userAgentMode(desktopMode
                        ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                        : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(desktopMode
                        ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                        : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build();

        final Tab tab = new Tab();
        tab.priv = actualPrivate;
        tab.containerId = identity;''',
"creation des sessions")

src = replace_once(src,
'''                        setupSession(privateMode, uri);''',
'''                        setupSession(tab.containerId, tab.priv, uri, false);''',
"nouvelle fenetre dans la meme identite")

src = replace_once(src,
'''            setupSession(privateMode, url);
            selectTab(tabs.size() - 1);''',
'''            setupSession(activeContainerId(), privateMode, url, false);
            selectTab(tabs.size() - 1);''',
"lien externe dans l'identite active")

src = replace_once(src,
'''    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab t = tabs.get(index);
        if (!t.priv) toTrash("onglet", t.title, t.url);

        // Le dernier onglet n'est pas ferme : on le ramene a l'accueil.
        if (tabs.size() == 1) {
            t.url = "";
            t.title = "";
            session.loadUri(homeUrl());
            return;
        }

        try { t.session.close(); } catch (Exception ignored) { }
        tabs.remove(index);
        selectTab(Math.min(index, tabs.size() - 1));
        Toast.makeText(this, tabs.size() + " onglet(s)", Toast.LENGTH_SHORT).show();
    }''',
'''    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab t = tabs.get(index);
        String closedIdentity = t.containerId;
        if (!t.priv) toTrash("onglet", t.title, t.url);

        // Un dernier onglet persistant retourne a l'accueil. Un onglet
        // ephemere est vraiment detruit afin de purger son contexte.
        if (tabs.size() == 1 && !ContainerManager.isEphemeral(closedIdentity)) {
            t.url = "";
            t.title = "";
            session.loadUri(homeUrl());
            return;
        }

        try { t.session.close(); } catch (Exception ignored) { }
        tabs.remove(index);
        cleanupEphemeralIfUnused(closedIdentity);

        if (tabs.isEmpty()) {
            setupSession(ContainerManager.defaultId(this), false, null, false);
        }
        selectTab(Math.min(index, tabs.size() - 1));
        Toast.makeText(this, tabs.size() + " onglet(s)", Toast.LENGTH_SHORT).show();
    }''',
"fermeture d'un onglet")

src = replace_once(src,
'''    private void updateTabButton() {
        if (tabButton == null) return;
        tabButton.setText(String.valueOf(tabs.size()));
        tabButton.setTextColor(privateMode ? 0xFF8AB4F8 : 0xFFE8EAEE);
    }''',
'''    private void updateTabButton() {
        if (tabButton == null) return;
        tabButton.setText(String.valueOf(tabs.size()));
        tabButton.setTextColor(ContainerManager.color(activeContainerId()));
        tabButton.setContentDescription(tabs.size() + " onglet(s), identite "
                + ContainerManager.name(activeContainerId()));
    }''',
"badge des onglets")

src = replace_once(src,
'''            String mark = (i == active ? "\\u25CF" : (t.priv ? "\\u25D1" : "\\u25CB"));
            String host = t.url.isEmpty() ? "vide" : t.url;
            if (host.length() > 46) host = host.substring(0, 46) + "…";
            m.add(mark, tabLabel(t), host, () -> selectTab(index));''',
'''            String mark = (i == active ? "\\u25CF" : ContainerManager.symbol(t.containerId));
            String host = t.url.isEmpty() ? "vide" : t.url;
            if (host.length() > 38) host = host.substring(0, 38) + "…";
            m.add(mark, tabLabel(t),
                  ContainerManager.name(t.containerId) + " \\u00B7 " + host,
                  () -> selectTab(index));''',
"libelle des onglets")

src = replace_once(src,
'''        m.add("\\u002B", "Nouvel onglet", () -> {
            setupSession(false, null);
            selectTab(tabs.size() - 1);
        });
        m.add("\\u25D1", "Nouvel onglet prive", () -> {
            setupSession(true, null);
            selectTab(tabs.size() - 1);
        });''',
'''        m.add("\\u002B", "Nouvel onglet",
              "identite par defaut \\u00B7 "
                      + ContainerManager.name(ContainerManager.defaultId(this)), () -> {
            setupSession(false, null);
            selectTab(tabs.size() - 1);
        });
        m.add("\\u25C8", "Nouvel onglet dans une identite",
              this::showNewTabIdentityPicker);
        m.add("\\u25D1", "Nouvel onglet prive", "identite Temporaire", () -> {
            setupSession(true, null);
            selectTab(tabs.size() - 1);
        });''',
"actions de creation d'onglet")

src = replace_once(src,
'''        tabs.clear();
        tabs.add(keep);
        selectTab(0);
    }''',
'''        tabs.clear();
        tabs.add(keep);
        selectTab(0);
        for (ContainerManager.Identity identity : ContainerManager.all()) {
            if (identity.ephemeral) cleanupEphemeralIfUnused(identity.id);
        }
    }''',
"nettoyage apres fermeture des autres onglets")

src = replace_once(src,
'''                o.put("url", t.url);
                o.put("title", t.title);
                arr.put(o);''',
'''                o.put("url", t.url);
                o.put("title", t.title);
                o.put("container", t.containerId);
                arr.put(o);''',
"sauvegarde de l'identite")

src = replace_once(src,
'''                // Paresseux : l'onglet existe, la page attendra sa selection.
                setupSession(false, u, true);
                tabs.get(tabs.size() - 1).title = o.optString("title", "");''',
'''                // Paresseux : l'onglet existe, la page attendra sa selection.
                String identity = ContainerManager.normalize(
                        o.optString("container", ContainerManager.defaultId(this)));
                // Une ancienne sauvegarde ne doit jamais ressusciter un contexte prive.
                if (ContainerManager.isPrivate(identity)) {
                    identity = ContainerManager.defaultId(this);
                }
                setupSession(identity, false, u, true);
                tabs.get(tabs.size() - 1).title = o.optString("title", "");''',
"restauration de l'identite")

src = replace_once(src,
'''            .sub("\\u25A5", "Onglets", tabs.size() + " ouvert(s)", this::showTabs)
            .sub("\\u25A4", "Page", pageHost(), this::showPageMenu)''',
'''            .sub("\\u25A5", "Onglets", tabs.size() + " ouvert(s)", this::showTabs)
            .sub("\\u25C8", "Identites", ContainerManager.name(activeContainerId()),
                 this::showIdentityMenu)
            .sub("\\u25A4", "Page", pageHost(), this::showPageMenu)''',
"entree Identites du menu")

identity_methods = r'''
    // =======================================================================
    //  Identites / conteneurs GeckoView
    // =======================================================================
    private String activeContainerId() {
        if (active >= 0 && active < tabs.size()) {
            return ContainerManager.normalize(tabs.get(active).containerId);
        }
        return ContainerManager.defaultId(this);
    }

    private boolean hasTabsForIdentity(String identity) {
        String normalized = ContainerManager.normalize(identity);
        for (Tab tab : tabs) {
            if (normalized.equals(ContainerManager.normalize(tab.containerId))) return true;
        }
        return false;
    }

    private void cleanupEphemeralIfUnused(String identity) {
        String normalized = ContainerManager.normalize(identity);
        if (!ContainerManager.isEphemeral(normalized) || hasTabsForIdentity(normalized)) return;
        try {
            sRuntime.getStorageController().clearDataForSessionContext(
                    ContainerManager.contextId(normalized));
        } catch (Throwable ignored) { }
    }

    private void showIdentityMenu() {
        final String current = activeContainerId();
        new Menus(this, "Identites")
            .add(ContainerManager.symbol(current), "Identite actuelle",
                 ContainerManager.name(current) + " \u00B7 "
                         + ContainerManager.description(current),
                 () -> showIdentityInfo(current))
            .add("\u002B", "Nouvel onglet dans une identite",
                 this::showNewTabIdentityPicker)
            .add("\u21C4", "Dupliquer cette page ailleurs",
                 this::showDuplicateIdentityPicker)
            .sub("\u2605", "Identite par defaut",
                 ContainerManager.name(ContainerManager.defaultId(this)),
                 this::showDefaultIdentityPicker)
            .add("\u2327", "Effacer les donnees de cette identite",
                 ContainerManager.name(current), () -> confirmClearIdentity(current))
            .add("\u24D8", "Comment fonctionne l'isolation", this::identityHelp)
            .back(this::showMenu)
            .show();
    }

    private void showIdentityPicker(String title,
                                    java.util.function.Consumer<String> selected,
                                    Runnable back, boolean defaultsOnly) {
        Menus menu = new Menus(this, title);
        for (ContainerManager.Identity identity : ContainerManager.all()) {
            if (defaultsOnly && !identity.canBeDefault) continue;
            final String id = identity.id;
            menu.add(identity.symbol, identity.name, identity.description,
                    () -> selected.accept(id));
        }
        menu.back(back).show();
    }

    private void showNewTabIdentityPicker() {
        showIdentityPicker("Nouvel onglet", id -> {
            setupSession(id, ContainerManager.isPrivate(id), null, false);
            selectTab(tabs.size() - 1);
            Toast.makeText(this, "Nouvel onglet : " + ContainerManager.name(id),
                    Toast.LENGTH_SHORT).show();
        }, this::showTabs, false);
    }

    private void showDuplicateIdentityPicker() {
        final String target = currentUrl.isEmpty() || currentUrl.startsWith("moz-extension://")
                ? null : currentUrl;
        showIdentityPicker("Dupliquer dans", id -> {
            setupSession(id, ContainerManager.isPrivate(id), target, false);
            selectTab(tabs.size() - 1);
            Toast.makeText(this, "Page ouverte dans " + ContainerManager.name(id),
                    Toast.LENGTH_SHORT).show();
        }, this::showIdentityMenu, false);
    }

    private void showDefaultIdentityPicker() {
        showIdentityPicker("Identite par defaut", id -> {
            ContainerManager.setDefault(this, id);
            Toast.makeText(this, "Identite par defaut : " + ContainerManager.name(id),
                    Toast.LENGTH_SHORT).show();
            showIdentityMenu();
        }, this::showIdentityMenu, true);
    }

    private void showIdentityInfo(String identity) {
        ContainerManager.Identity item = ContainerManager.find(identity);
        Menus.info(this, item.name,
                item.description + "\n\nContexte GeckoView : "
                + ContainerManager.contextId(item.id)
                + "\n\nLes cookies, connexions et stockages de sites sont partages "
                + "uniquement entre les onglets de cette identite.");
    }

    private void identityHelp() {
        Menus.info(this, "Identites isolees",
                "Chaque identite utilise un contextId GeckoView distinct. Cela separe "
              + "les cookies, les comptes connectes et le stockage local. Vous pouvez "
              + "donc ouvrir le meme site avec plusieurs comptes en meme temps.\n\n"
              + "Temporaire et Anonyme utilisent aussi le mode prive et sont purges "
              + "apres leur dernier onglet. Le nom Anonyme concerne le stockage local : "
              + "il ne masque pas votre adresse IP. Activez Tor separement pour router "
              + "le trafic par Orbot.\n\n"
              + "Lors de la premiere utilisation, les identites commencent avec un "
              + "nouvel espace de cookies : certains sites demanderont de vous reconnecter.");
    }

    private void confirmClearIdentity(final String identity) {
        final String normalized = ContainerManager.normalize(identity);
        Menus.dialog(this)
            .setTitle("Effacer " + ContainerManager.name(normalized) + " ?")
            .setMessage("Tous les onglets de cette identite seront fermes, puis ses "
                      + "cookies, connexions et stockages locaux seront supprimes. "
                      + "Les autres identites ne seront pas touchees.")
            .setPositiveButton("Effacer", (d, w) -> clearIdentityData(normalized))
            .setNegativeButton("Annuler", null)
            .show();
    }

    private void clearIdentityData(String identity) {
        String normalized = ContainerManager.normalize(identity);
        for (int i = tabs.size() - 1; i >= 0; i--) {
            Tab tab = tabs.get(i);
            if (!normalized.equals(ContainerManager.normalize(tab.containerId))) continue;
            try { tab.session.close(); } catch (Throwable ignored) { }
            tabs.remove(i);
        }

        try {
            sRuntime.getStorageController().clearDataForSessionContext(
                    ContainerManager.contextId(normalized));
        } catch (Throwable error) {
            Toast.makeText(this, "Nettoyage partiel : " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }

        if (tabs.isEmpty()) {
            setupSession(ContainerManager.defaultId(this), false, null, false);
        }
        selectTab(Math.min(Math.max(active, 0), tabs.size() - 1));
        Toast.makeText(this, "Donnees effacees : " + ContainerManager.name(normalized),
                Toast.LENGTH_SHORT).show();
    }
'''

src = replace_once(src,
'''    private String pageHost() {''',
identity_methods + '''
    private String pageHost() {''',
"methodes des identites")

# Validation minimale avant ecriture.
for required in (
    "ContainerManager.contextId(identity)",
    "private void showIdentityMenu()",
    'o.put("container", t.containerId)',
    "clearDataForSessionContext",
):
    if required not in src:
        fail(f"validation interne echouee: {required}")

if src.count("{") != src.count("}"):
    fail("accolades desequilibrees apres modification")
if src.count("(") != src.count(")"):
    fail("parentheses desequilibrees apres modification")

if not BACKUP.exists():
    shutil.copy2(MAIN, BACKUP)
MAIN.write_text(src, encoding="utf-8")

passation = ROOT / "PASSATION.md"
marker = "40. Identites et conteneurs GeckoView"
if passation.exists():
    doc = passation.read_text(encoding="utf-8")
    if marker not in doc:
        doc += ("\n\n" + marker + " : six espaces de navigation "
                "(Personnel, Travail, Banque, Reseaux sociaux, Temporaire, "
                "Anonyme) fondes sur GeckoSessionSettings.contextId. Les cookies "
                "et stockages sont separes, l'identite est sauvegardee avec les "
                "onglets persistants, et un contexte peut etre purge sans toucher "
                "les autres. Temporaire et Anonyme utilisent le mode prive.\n")
        passation.write_text(doc, encoding="utf-8")

print("Identites GeckoView installees dans MainActivity.java")
print(f"Sauvegarde: {BACKUP}")
