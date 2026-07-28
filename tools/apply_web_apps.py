#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ajoute les applications web installables et leur mode autonome."""

from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/example/geckobrowser/MainActivity.java"
MANAGER = ROOT / "app/src/main/java/com/example/geckobrowser/WebAppManager.java"
BACKUP = MAIN.with_name("MainActivity.java.before-web-apps")
MARKER = "WEB_APPS_V1"


def fail(message: str) -> None:
    print(f"ERREUR: {message}", file=sys.stderr)
    sys.exit(1)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"ancre {label!r} trouvee {count} fois au lieu d'une")
    return text.replace(old, new, 1)


if not MAIN.is_file():
    fail(f"fichier introuvable: {MAIN}")
if not MANAGER.is_file():
    fail(f"fichier introuvable: {MANAGER}")

src = MAIN.read_text(encoding="utf-8")
if MARKER in src:
    print("Applications web deja installees.")
    sys.exit(0)

# L'overlay vise la version réellement publiée après l'étape 9. Les contrôles
# restent volontairement limités aux fonctions dont cette étape dépend.
for required in (
    "MEDIA_HUB_V1",
    "PASSWORD_VAULT_V1",
    "PrivacyCockpit.attach",
    "SmartSelectionDelegate",
    "SessionStore.write",
):
    if required not in src:
        fail(f"la version attendue de MainActivity.java manque {required}")

if not BACKUP.exists():
    shutil.copy2(MAIN, BACKUP)

# ---------------------------------------------------------------------------
# Etat propre à chaque onglet.
# ---------------------------------------------------------------------------
src = replace_once(
    src,
    '''        /** Langue detectee par Gecko, pour la traduction de page. */
        String langTag;
    }''',
    '''        /** Langue detectee par Gecko, pour la traduction de page. */
        String langTag;

        // WEB_APPS_V1 — onglet ouvert comme application web autonome.
        boolean webAppMode = false;
        String webAppId = "";
        String webAppName = "";
        String webAppScope = "";
        String webAppTheme = "";
        String webAppDisplay = "standalone";
    }''',
    "champs Tab",
)

src = replace_once(
    src,
    '''    // PASSWORD_VAULT_V1 — coffre chiffre et remplissage GeckoView.
    private PasswordVault passwordVault;

    private static final int REQ_FILE = 8123;''',
    '''    // PASSWORD_VAULT_V1 — coffre chiffre et remplissage GeckoView.
    private PasswordVault passwordVault;

    // WEB_APPS_V1 — manifestes, catalogue et raccourcis Android.
    private WebAppManager webApps;

    private static final int REQ_FILE = 8123;''',
    "champ WebAppManager",
)

# Le gestionnaire doit exister avant la création de la première session.
src = replace_once(
    src,
    '''        try {
            sRuntime.getSettings().setLoginAutofillEnabled(
                    passwordVault.isAutofillEnabled());
        } catch (Throwable ignored) { }
        installBlocker();''',
    '''        try {
            sRuntime.getSettings().setLoginAutofillEnabled(
                    passwordVault.isAutofillEnabled());
        } catch (Throwable ignored) { }
        webApps = new WebAppManager(this);
        installBlocker();''',
    "initialisation WebAppManager",
)

# ---------------------------------------------------------------------------
# Raccourcis d'écran d'accueil.
# ---------------------------------------------------------------------------
src = replace_once(
    src,
    '''        if (intent == null) return;
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return;

        android.net.Uri data = intent.getData();''',
    '''        if (intent == null) return;
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return;

        WebAppManager.App launchedApp = WebAppManager.fromIntent(intent);
        if (launchedApp != null) {
            WebAppManager.consumeIntent(intent);
            openWebApp(launchedApp, true);
            return;
        }

        android.net.Uri data = intent.getData();''',
    "ouverture raccourci web",
)

# ---------------------------------------------------------------------------
# Navigation et manifeste PWA par session.
# ---------------------------------------------------------------------------
src = replace_once(
    src,
    '''                if (url == null) return;
                tab.url = url;
                scheduleSessionSave();
                // Un onglet d'arriere-plan ne doit pas ecraser la barre d'adresse.
                if (s != session) return;
                currentUrl = url;
                urlBar.setText(url.startsWith("moz-extension://") ? "" : url);''',
    '''                if (url == null) return;
                tab.url = url;
                webApps.onLocation(s, url);
                boolean leftWebApp = tab.webAppMode
                        && (url.startsWith("http://") || url.startsWith("https://"))
                        && !WebAppManager.inScope(url, tab.webAppScope);
                if (leftWebApp) {
                    tab.webAppMode = false;
                    tab.webAppId = "";
                }
                scheduleSessionSave();
                // Un onglet d'arriere-plan ne doit pas ecraser la barre d'adresse.
                if (s != session) return;
                currentUrl = url;
                urlBar.setText(url.startsWith("moz-extension://") ? "" : url);
                if (leftWebApp) {
                    applyWebAppChrome(tab);
                    Toast.makeText(MainActivity.this,
                            "Lien ouvert hors de l'application web",
                            Toast.LENGTH_SHORT).show();
                }''',
    "suivi de portee web app",
)

src = replace_once(
    src,
    '''            @Override
            public void onTitleChange(GeckoSession s, String title) {
                tab.title = title == null ? "" : title;
                scheduleSessionSave();
                if (s == session) currentTitle = tab.title;
            }

            // Fichier que Gecko ne peut pas afficher : on l'enregistre.''',
    '''            @Override
            public void onTitleChange(GeckoSession s, String title) {
                tab.title = title == null ? "" : title;
                scheduleSessionSave();
                if (s == session) currentTitle = tab.title;
            }

            @Override
            public void onWebAppManifest(GeckoSession s, JSONObject manifest) {
                webApps.onManifest(s, manifest, tab.url, tab.title);
            }

            // Fichier que Gecko ne peut pas afficher : on l'enregistre.''',
    "reception manifeste web",
)

# ---------------------------------------------------------------------------
# Sélection, fermeture et restauration des onglets.
# ---------------------------------------------------------------------------
src = replace_once(
    src,
    '''        urlBar.setText(currentUrl.startsWith("moz-extension://") ? "" : currentUrl);
        updateTabButton();''',
    '''        urlBar.setText(currentUrl.startsWith("moz-extension://") ? "" : currentUrl);
        applyWebAppChrome(t);
        updateTabButton();''',
    "interface lors de la selection",
)

src = replace_once(
    src,
    '''        if (tabs.size() == 1) {
            t.url = "";
            t.title = "";
            session.loadUri(homeUrl());
            return;
        }''',
    '''        if (tabs.size() == 1) {
            t.url = "";
            t.title = "";
            t.webAppMode = false;
            t.webAppId = "";
            t.webAppName = "";
            t.webAppScope = "";
            t.webAppTheme = "";
            t.webAppDisplay = "standalone";
            applyWebAppChrome(t);
            session.loadUri(homeUrl());
            scheduleSessionSave();
            return;
        }''',
    "nettoyage dernier onglet",
)

src = replace_once(
    src,
    '''                o.put("url", t.url);
                o.put("title", t.title);
                if (t.state != null && !t.state.isEmpty()) o.put("state", t.state);''',
    '''                o.put("url", t.url);
                o.put("title", t.title);
                if (t.webAppMode) {
                    o.put("webAppMode", true);
                    o.put("webAppId", t.webAppId);
                    o.put("webAppName", t.webAppName);
                    o.put("webAppScope", t.webAppScope);
                    o.put("webAppTheme", t.webAppTheme);
                    o.put("webAppDisplay", t.webAppDisplay);
                }
                if (t.state != null && !t.state.isEmpty()) o.put("state", t.state);''',
    "sauvegarde web app",
)

src = replace_once(
    src,
    '''                setupSession(false, u, true, encoded);
                tabs.get(tabs.size() - 1).title = o.optString("title", "");
                if (i == wanted) target = tabs.size() - 1;''',
    '''                setupSession(false, u, true, encoded);
                Tab restored = tabs.get(tabs.size() - 1);
                restored.title = o.optString("title", "");
                restored.webAppMode = o.optBoolean("webAppMode", false);
                restored.webAppId = o.optString("webAppId", "");
                restored.webAppName = o.optString("webAppName", "");
                restored.webAppScope = o.optString("webAppScope", "");
                restored.webAppTheme = o.optString("webAppTheme", "");
                restored.webAppDisplay = o.optString("webAppDisplay", "standalone");
                if (restored.webAppMode
                        && !WebAppManager.inScope(u, restored.webAppScope)) {
                    restored.webAppMode = false;
                }
                if (i == wanted) target = tabs.size() - 1;''',
    "restauration web app",
)

# ---------------------------------------------------------------------------
# Coexistence avec le plein écran et le Picture-in-Picture.
# ---------------------------------------------------------------------------
src = replace_once(
    src,
    '''        if (mediaHub != null) mediaHub.onPipModeChanged(inPip);
        setBrowserChromeVisible(!inPip && !mediaFullscreen);''',
    '''        if (mediaHub != null) mediaHub.onPipModeChanged(inPip);
        if (inPip) {
            if (webApps != null) webApps.hideStandaloneBar();
            setBrowserChromeVisible(false);
        } else if (!mediaFullscreen) {
            applyWebAppChrome(currentTab());
        }''',
    "sortie PiP",
)

src = replace_once(
    src,
    '''        if (passwordVault != null) passwordVault.onActivityDestroyed(this);
        if (mediaHub != null) mediaHub.release();''',
    '''        if (passwordVault != null) passwordVault.onActivityDestroyed(this);
        if (webApps != null) webApps.release();
        if (mediaHub != null) mediaHub.release();''',
    "liberation WebAppManager",
)

src = replace_once(
    src,
    '''        } catch (Throwable e) {
            setBrowserChromeVisible(!mediaFullscreen);
            Toast.makeText(this, "Image dans l'image indisponible",''',
    '''        } catch (Throwable e) {
            if (!mediaFullscreen) applyWebAppChrome(currentTab());
            Toast.makeText(this, "Image dans l'image indisponible",''',
    "erreur PiP",
)

old_fullscreen = '''    private void setMediaFullscreen(boolean enabled) {
        mediaFullscreen = enabled;
        setBrowserChromeVisible(!enabled && !isInPictureInPictureMode());
        android.view.View decor = getWindow().getDecorView();
        if (enabled) {
            decor.setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                  | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                  | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            decor.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }'''
new_fullscreen = '''    private void setMediaFullscreen(boolean enabled) {
        mediaFullscreen = enabled;
        if (enabled) {
            if (webApps != null) webApps.hideStandaloneBar();
            setBrowserChromeVisible(false);
        } else if (!isInPictureInPictureMode()) {
            applyWebAppChrome(currentTab());
        }
        android.view.View decor = getWindow().getDecorView();
        if (enabled) {
            decor.setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                  | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                  | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            decor.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }'''
src = replace_once(src, old_fullscreen, new_fullscreen, "plein ecran et web app")

# ---------------------------------------------------------------------------
# Contrôleur MainActivity du mode application.
# ---------------------------------------------------------------------------
web_app_methods = r'''
    // =======================================================================
    //  Applications web installables
    // =======================================================================
    private Tab currentTab() {
        return active >= 0 && active < tabs.size() ? tabs.get(active) : null;
    }

    private boolean isCurrentWebAppMode() {
        Tab tab = currentTab();
        return tab != null && tab.webAppMode;
    }

    private WebAppManager.App webAppFor(Tab tab) {
        if (tab == null) return null;
        WebAppManager.App app = new WebAppManager.App();
        app.id = tab.webAppId;
        app.name = tab.webAppName == null || tab.webAppName.isEmpty()
                ? tabLabel(tab) : tab.webAppName;
        app.startUrl = tab.url;
        app.scope = tab.webAppScope;
        app.themeColor = tab.webAppTheme;
        app.display = tab.webAppDisplay;
        app.manifestBacked = true;
        return app;
    }

    private void openWebApp(WebAppManager.App app, boolean standalone) {
        if (app == null || app.startUrl == null || app.startUrl.isEmpty()) return;
        if (!standalone) {
            setupSession(false, app.startUrl);
            selectTab(tabs.size() - 1);
            return;
        }

        Tab target;
        if (tabs.size() == 1 && active == 0
                && (currentUrl.isEmpty() || currentUrl.startsWith("moz-extension://"))) {
            target = tabs.get(0);
            target.pending = null;
            target.pendingState = null;
            target.session.loadUri(app.startUrl);
        } else {
            setupSession(false, app.startUrl);
            target = tabs.get(tabs.size() - 1);
        }

        target.webAppMode = true;
        target.webAppId = app.id == null ? "" : app.id;
        target.webAppName = app.name == null ? "Application web" : app.name;
        target.webAppScope = app.scope == null || app.scope.isEmpty()
                ? app.startUrl : app.scope;
        target.webAppTheme = app.themeColor == null ? "" : app.themeColor;
        target.webAppDisplay = app.display == null ? "standalone" : app.display;
        selectTab(tabs.indexOf(target));
        scheduleSessionSave();
    }

    private void showWebApps() {
        webApps.show(session, currentUrl, currentTitle, privateMode,
                this::showMenu, this::openWebApp);
    }

    private void applyWebAppChrome(Tab tab) {
        if (webApps == null) return;
        if (mediaFullscreen || isInPictureInPictureMode()) {
            webApps.hideStandaloneBar();
            return;
        }
        if (tab != null && tab.webAppMode) {
            setBrowserChromeVisible(false);
            android.view.ViewGroup root = findViewById(R.id.root_container);
            android.view.View content = findViewById(R.id.browser_column);
            webApps.showStandaloneBar(root, content, webAppFor(tab),
                    () -> exitCurrentWebAppMode(false),
                    () -> exitCurrentWebAppMode(true));
        } else {
            webApps.hideStandaloneBar();
            setBrowserChromeVisible(true);
        }
    }

    private void exitCurrentWebAppMode(boolean close) {
        Tab tab = currentTab();
        if (tab == null) return;
        tab.webAppMode = false;
        tab.webAppId = "";
        applyWebAppChrome(tab);
        scheduleSessionSave();
        if (close && tabs.size() > 1) closeTab(active);
    }
'''

src = replace_once(
    src,
    '''    // =======================================================================
    //  Coffre de mots de passe
    // =======================================================================''',
    web_app_methods + '''
    // =======================================================================
    //  Coffre de mots de passe
    // =======================================================================''',
    "methodes applications web",
)

# Menu principal.
src = replace_once(
    src,
    '''            .sub("\\u25B6", "Multimedia", mediaHub.summary(),
                 () -> mediaHub.showMenu(this::showMenu))
            .sub("\\u25A4", "Page", pageHost(), this::showPageMenu)''',
    '''            .sub("\\u25B6", "Multimedia", mediaHub.summary(),
                 () -> mediaHub.showMenu(this::showMenu))
            .sub("\\u25A3", "Applications web", webApps.summary(session, currentUrl),
                 this::showWebApps)
            .sub("\\u25A4", "Page", pageHost(), this::showPageMenu)''',
    "menu applications web",
)

# Activation du traitement des manifestes dans Gecko.
src = replace_once(
    src,
    '''                .javaScriptEnabled(true)
                .loginAutofillEnabled(''',
    '''                .javaScriptEnabled(true)
                .webManifest(true)
                .loginAutofillEnabled(''',
    "activation manifestes",
)

# Le bouton Retour ferme d'abord le mode application lorsqu'il n'y a plus
# d'historique interne à remonter.
src = replace_once(
    src,
    '''        } else if (canGoBack) {
            session.goBack();
        } else if (tabs.size() > 1) {''',
    '''        } else if (canGoBack) {
            session.goBack();
        } else if (isCurrentWebAppMode()) {
            exitCurrentWebAppMode(tabs.size() > 1);
        } else if (tabs.size() > 1) {''',
    "retour mode application",
)

MAIN.write_text(src, encoding="utf-8")
print("Applications web installees.")
print(f"Sauvegarde: {BACKUP}")
