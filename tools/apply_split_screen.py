#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Applique l'etape 11 : deux onglets visibles simultanement."""

from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/geckobrowser"
MAIN = JAVA / "MainActivity.java"
MANAGER = JAVA / "SplitScreenManager.java"
BACKUP = MAIN.with_name("MainActivity.java.before-split-screen")
MARKER = "SPLIT_SCREEN_V1"


def fail(message: str) -> None:
    print(f"ERREUR: {message}", file=sys.stderr)
    sys.exit(1)


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        fail(f"{label}: ancre attendue une fois, trouvee {count} fois")
    return source.replace(old, new, 1)


if not MAIN.exists():
    fail(f"MainActivity introuvable: {MAIN}")
if not MANAGER.exists():
    fail(f"SplitScreenManager introuvable: {MANAGER}")

text = MAIN.read_text(encoding="utf-8")
if MARKER in text:
    print("Ecran partage deja installe: aucune modification.")
    sys.exit(0)

required = ("WEB_APPS_V1", "PASSWORD_VAULT_V1", "MEDIA_HUB_V1")
missing = [value for value in required if value not in text]
if missing:
    fail("etapes precedentes absentes: " + ", ".join(missing))

if not BACKUP.exists():
    shutil.copy2(MAIN, BACKUP)

text = replace_once(
    text,
    '''    // WEB_APPS_V1 — manifestes, catalogue et raccourcis Android.
    private WebAppManager webApps;

    private static final int REQ_FILE = 8123;''',
    '''    // WEB_APPS_V1 — manifestes, catalogue et raccourcis Android.
    private WebAppManager webApps;

    // SPLIT_SCREEN_V1 — deux onglets visibles et redimensionnables.
    private SplitScreenManager splitScreen;

    private static final int REQ_FILE = 8123;''',
    "champs de l'ecran partage",
)

text = replace_once(
    text,
    '''        webApps = new WebAppManager(this);
        installBlocker();''',
    '''        webApps = new WebAppManager(this);
        splitScreen = new SplitScreenManager(this, geckoView,
                new SplitScreenManager.Host() {
                    @Override
                    public void onPaneFocused(GeckoSession target) {
                        focusSplitSession(target);
                    }

                    @Override
                    public String titleFor(GeckoSession target) {
                        return splitTitleFor(target);
                    }
                });
        installBlocker();''',
    "initialisation de l'ecran partage",
)

text = replace_once(
    text,
    '''            session.open(sRuntime);
            geckoView.setSession(session);

            // Le compositeur n'existe qu'une fois la session rattachee a la vue :''',
    '''            session.open(sRuntime);
            if (splitScreen != null && splitScreen.isActive()) {
                splitScreen.selectSession(session);
            } else {
                attachPrimarySession(session);
            }

            // Le compositeur n'existe qu'une fois la session rattachee a la vue :''',
    "rattachement de la nouvelle session",
)

text = replace_once(
    text,
    '''    private void applyTabActivity() {
        for (int i = 0; i < tabs.size(); i++) {
            GeckoSession s = tabs.get(i).session;
            if (s == null || !s.isOpen()) continue;
            try {
                s.setActive(i == active);
                s.setFocused(i == active);
            } catch (Throwable ignored) { }
        }
    }

    private void selectTab(int index) {''',
    '''    private void attachPrimarySession(GeckoSession target) {
        if (target == null || geckoView.getSession() == target) return;
        try {
            if (geckoView.getSession() != null) geckoView.releaseSession();
        } catch (Throwable ignored) { }
        geckoView.setSession(target);
    }

    private void applyTabActivity() {
        for (int i = 0; i < tabs.size(); i++) {
            GeckoSession s = tabs.get(i).session;
            if (s == null || !s.isOpen()) continue;
            try {
                boolean visible = i == active
                        || (splitScreen != null && splitScreen.isVisible(s));
                s.setActive(visible);
                s.setFocused(i == active);
            } catch (Throwable ignored) { }
        }
    }

    private void selectTab(int index) {''',
    "activite des deux sessions",
)

text = replace_once(
    text,
    '''        geckoView.setSession(session);
        applyTabActivity();

        if (firstOpen) {''',
    '''        if (splitScreen != null && splitScreen.isActive()) {
            splitScreen.selectSession(session);
        } else {
            attachPrimarySession(session);
        }
        applyTabActivity();

        if (firstOpen) {''',
    "selection d'un onglet dans un volet",
)

text = replace_once(
    text,
    '''        applyWebAppChrome(t);
        updateTabButton();''',
    '''        applyWebAppChrome(t);
        if (splitScreen != null && splitScreen.isActive()) {
            if (webApps != null) webApps.hideStandaloneBar();
            setBrowserChromeVisible(true);
            splitScreen.refreshLabels();
        }
        updateTabButton();''',
    "interface apres selection",
)

text = replace_once(
    text,
    '''        Tab t = tabs.get(index);
        if (!t.priv) toTrash("onglet", t.title, t.url);''',
    '''        Tab t = tabs.get(index);
        if (splitScreen != null && splitScreen.contains(t.session)) {
            splitScreen.exit();
        }
        if (!t.priv) toTrash("onglet", t.title, t.url);''',
    "fermeture d'un volet",
)

text = replace_once(
    text,
    '''    private void closeOthers() {
        Tab keep = tabs.get(active);''',
    '''    private void closeOthers() {
        if (splitScreen != null && splitScreen.isActive()) splitScreen.exit();
        Tab keep = tabs.get(active);''',
    "fermeture des autres onglets",
)

text = replace_once(
    text,
    '''        tabButton.setText(String.valueOf(tabs.size()));''',
    '''        tabButton.setText(splitScreen != null && splitScreen.isActive()
                ? "2/" + tabs.size() : String.valueOf(tabs.size()));''',
    "compteur d'onglets",
)

text = replace_once(
    text,
    '''            String mark = (i == active ? "\\u25CF" : (t.priv ? "\\u25D1" : "\\u25CB"));''',
    '''            String mark = i == active ? "\\u25CF"
                    : (splitScreen != null && splitScreen.contains(t.session) ? "\\u25C9"
                    : (t.priv ? "\\u25D1" : "\\u25CB"));''',
    "marquage des volets",
)

text = replace_once(
    text,
    '''    @Override
    protected void onDestroy() {
        if (passwordVault != null) passwordVault.onActivityDestroyed(this);''',
    '''    @Override
    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (splitScreen != null) splitScreen.onConfigurationChanged(configuration);
    }

    @Override
    protected void onDestroy() {
        if (passwordVault != null) passwordVault.onActivityDestroyed(this);
        if (splitScreen != null) splitScreen.release();''',
    "cycle de vie",
)

text = replace_once(
    text,
    '''    void requestMediaPictureInPicture(long width, long height) {
        if (android.os.Build.VERSION.SDK_INT < 26 || isInPictureInPictureMode()) return;
        try {''',
    '''    void requestMediaPictureInPicture(long width, long height) {
        if (android.os.Build.VERSION.SDK_INT < 26 || isInPictureInPictureMode()) return;
        if (splitScreen != null && splitScreen.isActive()) splitScreen.exit();
        try {''',
    "PiP et ecran partage",
)

text = replace_once(
    text,
    '''    private void setMediaFullscreen(boolean enabled) {
        mediaFullscreen = enabled;''',
    '''    private void setMediaFullscreen(boolean enabled) {
        if (enabled && splitScreen != null && splitScreen.isActive()) splitScreen.exit();
        mediaFullscreen = enabled;''',
    "plein ecran et ecran partage",
)

text = replace_once(
    text,
    '''    private void openWebApp(WebAppManager.App app, boolean standalone) {
        if (app == null || app.startUrl == null || app.startUrl.isEmpty()) return;''',
    '''    private void openWebApp(WebAppManager.App app, boolean standalone) {
        if (app == null || app.startUrl == null || app.startUrl.isEmpty()) return;
        if (standalone && splitScreen != null && splitScreen.isActive()) {
            splitScreen.exit();
        }''',
    "applications web et ecran partage",
)

text = replace_once(
    text,
    '''        geckoView.setBackgroundColor(color);
        for (Tab tab : tabs) {''',
    '''        geckoView.setBackgroundColor(color);
        if (splitScreen != null) splitScreen.applyTheme(color);
        for (Tab tab : tabs) {''',
    "theme des deux volets",
)

split_methods = r'''
    // =======================================================================
    //  Ecran partage
    // =======================================================================
    private String splitTitleFor(GeckoSession target) {
        if (target == null) return "";
        for (Tab tab : tabs) {
            if (tab.session == target) return tabLabel(tab);
        }
        return "";
    }

    private int splitIndexFor(GeckoSession target) {
        if (target == null) return -1;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).session == target) return i;
        }
        return -1;
    }

    private void focusSplitSession(GeckoSession target) {
        int index = splitIndexFor(target);
        if (index < 0) return;

        hideFindBar();
        active = index;
        Tab tab = tabs.get(index);
        session = tab.session;
        privateMode = tab.priv;
        currentUrl = tab.url == null ? "" : tab.url;
        currentTitle = tab.title == null ? "" : tab.title;

        urlBar.setText(currentUrl.startsWith("moz-extension://") ? "" : currentUrl);
        if (webApps != null) webApps.hideStandaloneBar();
        setBrowserChromeVisible(true);
        applyTabActivity();
        updateTabButton();
        if (splitScreen != null) splitScreen.refreshLabels();
        scheduleSessionSave();
    }

    private void showSplitScreenMenu() {
        if (splitScreen == null) return;
        Menus menu = new Menus(this, "Ecran partage");

        if (!splitScreen.isActive()) {
            menu.add("\u25EB", "Choisir le second onglet",
                    tabs.size() > 1 ? "afficher deux pages" : "aucun autre onglet",
                    () -> chooseSplitTab(false));
            menu.add("\u002B", "Nouveau volet",
                    "ouvrir un nouvel onglet a cote", this::createSplitTab);
            menu.add("\u24D8", "Fonctionnement",
                    "touchez un volet pour lui donner le focus",
                    () -> Toast.makeText(this,
                            "La barre d'adresse, la recherche et les commandes agissent "
                          + "sur le volet entoure. Faites glisser la separation pour "
                          + "redimensionner les deux pages.",
                            Toast.LENGTH_LONG).show());
        } else {
            menu.add("\u21C4", "Permuter les volets", () -> splitScreen.swap());
            menu.add("\u25A3", "Remplacer le volet actif",
                    "par un autre onglet", () -> chooseSplitTab(true));
            menu.sub("\u2194", "Disposition", splitScreen.orientationName(),
                    this::showSplitOrientationMenu);
            menu.sub("\u00BD", "Repartition", splitScreen.ratioName(),
                    this::showSplitRatioMenu);
            menu.add("\u2715", "Quitter l'ecran partage", () -> {
                splitScreen.exit();
                applyTabActivity();
                applyWebAppChrome(currentTab());
                updateTabButton();
            });
        }
        menu.back(this::showMenu).show();
    }

    private void chooseSplitTab(boolean replaceFocused) {
        Menus menu = new Menus(this,
                replaceFocused ? "Remplacer le volet" : "Second volet");
        boolean any = false;
        for (int i = 0; i < tabs.size(); i++) {
            if (!replaceFocused && i == active) continue;
            final int index = i;
            final Tab tab = tabs.get(i);
            any = true;
            String detail = tab.url == null || tab.url.isEmpty() ? "vide" : tab.url;
            if (detail.length() > 48) detail = detail.substring(0, 47) + "\u2026";
            menu.add(tab.priv ? "\u25D1" : "\u25CB", tabLabel(tab), detail, () -> {
                if (replaceFocused && splitScreen.isActive()) selectTab(index);
                else startSplitWith(index);
            });
        }

        if (!any) menu.add("\u002B", "Creer un nouvel onglet", this::createSplitTab);
        menu.back(this::showSplitScreenMenu).show();
    }

    private void createSplitTab() {
        if (splitScreen != null && splitScreen.isActive()) {
            setupSession(false, null);
            selectTab(tabs.size() - 1);
            return;
        }
        int first = active;
        setupSession(false, null);
        int second = tabs.size() - 1;
        selectTab(first);
        startSplitWith(second);
    }

    private void startSplitWith(int secondIndex) {
        if (splitScreen == null || splitScreen.isActive()) return;
        if (active < 0 || active >= tabs.size()
                || secondIndex < 0 || secondIndex >= tabs.size()
                || secondIndex == active) return;
        if (mediaFullscreen || isInPictureInPictureMode()) {
            Toast.makeText(this, "Quittez d'abord le plein ecran",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        int firstIndex = active;
        Tab first = tabs.get(firstIndex);
        Tab second = tabs.get(secondIndex);
        first.webAppMode = false;
        second.webAppMode = false;
        if (webApps != null) webApps.hideStandaloneBar();
        setBrowserChromeVisible(true);

        selectTab(secondIndex);
        selectTab(firstIndex);

        if (splitScreen.start(first.session, second.session)) {
            focusSplitSession(first.session);
            applyTabActivity();
            updateTabButton();
            Toast.makeText(this, "Ecran partage active", Toast.LENGTH_SHORT).show();
        }
    }

    private void showSplitOrientationMenu() {
        new Menus(this, "Disposition")
            .add("A", "Automatique", "cote a cote en paysage, haut / bas en portrait",
                    () -> {
                        splitScreen.setOrientationMode(SplitScreenManager.ORIENTATION_AUTO);
                        showSplitScreenMenu();
                    })
            .add("\u2194", "Cote a cote", () -> {
                splitScreen.setOrientationMode(SplitScreenManager.ORIENTATION_HORIZONTAL);
                showSplitScreenMenu();
            })
            .add("\u2195", "Haut / bas", () -> {
                splitScreen.setOrientationMode(SplitScreenManager.ORIENTATION_VERTICAL);
                showSplitScreenMenu();
            })
            .back(this::showSplitScreenMenu)
            .show();
    }

    private void showSplitRatioMenu() {
        new Menus(this, "Repartition")
            .add("30", "30 / 70", () -> setSplitRatio(0.30f))
            .add("40", "40 / 60", () -> setSplitRatio(0.40f))
            .add("50", "50 / 50", () -> setSplitRatio(0.50f))
            .add("60", "60 / 40", () -> setSplitRatio(0.60f))
            .add("70", "70 / 30", () -> setSplitRatio(0.70f))
            .back(this::showSplitScreenMenu)
            .show();
    }

    private void setSplitRatio(float ratio) {
        splitScreen.setRatio(ratio);
        showSplitScreenMenu();
    }

'''

anchor = '''    // =======================================================================
    //  Applications web installables
    // ======================================================================='''
text = replace_once(text, anchor, split_methods + anchor,
                    "methodes de l'ecran partage")

text = replace_once(
    text,
    '''            .sub("\\u25A5", "Onglets", tabs.size() + " ouvert(s)", this::showTabs)
            .sub("\\u25B6", "Multimedia", mediaHub.summary(),''',
    '''            .sub("\\u25A5", "Onglets", tabs.size() + " ouvert(s)", this::showTabs)
            .sub("\\u25EB", "Ecran partage", splitScreen.summary(),
                 this::showSplitScreenMenu)
            .sub("\\u25B6", "Multimedia", mediaHub.summary(),''',
    "entree du menu principal",
)

MAIN.write_text(text, encoding="utf-8")

passation = ROOT / "PASSATION.md"
if passation.exists():
    value = passation.read_text(encoding="utf-8")
    note = ("## Etape 11 — ecran partage\n\n"
            "- `SplitScreenManager.java` gere deux surfaces GeckoView sans dupliquer les sessions.\n"
            "- `MainActivity.java.before-split-screen` conserve la version precedente.\n"
            "- Le volet entoure est celui controle par la barre d'adresse et les menus.\n")
    if "Etape 11 — ecran partage" not in value:
        passation.write_text(value.rstrip() + "\n\n" + note, encoding="utf-8")

print("Ecran partage installe.")
print("Sauvegarde:", BACKUP)
