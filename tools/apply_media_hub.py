#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Installe le centre multimedia, les commandes Android et le PiP."""

from pathlib import Path
import json
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/example/geckobrowser/MainActivity.java"
ANDROID_MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
EXT_MANIFEST = ROOT / "app/src/main/assets/adblock/manifest.json"
BACKUP = MAIN.with_name("MainActivity.java.before-media-hub")
MARKER = "MEDIA_HUB_V1"


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
if not ANDROID_MANIFEST.is_file():
    fail(f"fichier introuvable: {ANDROID_MANIFEST}")
if not EXT_MANIFEST.is_file():
    fail(f"fichier introuvable: {EXT_MANIFEST}")

src = MAIN.read_text(encoding="utf-8")
if MARKER in src:
    print("Centre multimedia deja installe.")
    sys.exit(0)

for required in ("PrivacyCockpit.attach", "SmartSelectionDelegate", "SessionStore"):
    if required not in src:
        fail(f"la version attendue de MainActivity.java manque {required}")

if not BACKUP.exists():
    shutil.copy2(MAIN, BACKUP)

# Champs et initialisation.
src = replace_once(
    src,
    """    private android.os.Handler sessionSaveHandler;
    private final Runnable sessionSaveRunnable = this::saveTabs;
""",
    """    private android.os.Handler sessionSaveHandler;
    private final Runnable sessionSaveRunnable = this::saveTabs;

    // MEDIA_HUB_V1 — lecture native, notification, plein ecran et PiP.
    private MediaHub mediaHub;
    private boolean mediaFullscreen = false;
""",
    "champs multimedia",
)

src = replace_once(
    src,
    """        sessionSaveHandler = new android.os.Handler(getMainLooper());

        if (sRuntime == null) {""",
    """        sessionSaveHandler = new android.os.Handler(getMainLooper());
        mediaHub = new MediaHub(this);

        if (sRuntime == null) {""",
    "initialisation MediaHub",
)

src = replace_once(
    src,
    """        tab.session = session;
        PrivacyCockpit.attach(tab.session);
""",
    """        tab.session = session;
        PrivacyCockpit.attach(tab.session);
        mediaHub.attach(tab.session);
""",
    "delegate multimedia",
)

# Plein ecran demande par la page.
src = replace_once(
    src,
    """            public void onFirstContentfulPaint(GeckoSession s) {
                // about:blank declenche aussi ce signal : ceder maintenant
                // decouvrirait la page vierge, blanche par defaut.
                if (s == session && isRealPage(tab.url)) hideSplash();
            }
        });""",
    """            public void onFirstContentfulPaint(GeckoSession s) {
                // about:blank declenche aussi ce signal : ceder maintenant
                // decouvrirait la page vierge, blanche par defaut.
                if (s == session && isRealPage(tab.url)) hideSplash();
            }

            @Override
            public void onFullScreen(GeckoSession s, boolean fullScreen) {
                if (s == session) setMediaFullscreen(fullScreen);
            }
        });""",
    "plein ecran Gecko",
)

# Entree de menu principale.
src = replace_once(
    src,
    """            .sub("\\u25A5", "Onglets", tabs.size() + " ouvert(s)", this::showTabs)
            .sub("\\u25A4", "Page", pageHost(), this::showPageMenu)""",
    """            .sub("\\u25A5", "Onglets", tabs.size() + " ouvert(s)", this::showTabs)
            .sub("\\u25B6", "Multimedia", mediaHub.summary(),
                 () -> mediaHub.showMenu(this::showMenu))
            .sub("\\u25A4", "Page", pageHost(), this::showPageMenu)""",
    "menu multimedia",
)

# Methodes de liaison entre MediaHub et les onglets / l'activite.
bridge = r'''
    // =======================================================================
    //  Multimedia, plein ecran et image dans l'image
    // =======================================================================
    String mediaTitleFor(GeckoSession target) {
        if (target == null) return currentTitle;
        for (Tab tab : tabs) {
            if (tab.session == target) {
                if (tab.title != null && !tab.title.isEmpty()) return tab.title;
                return tabLabel(tab);
            }
        }
        return currentTitle;
    }

    void openMediaSession(GeckoSession target) {
        if (target == null) return;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).session == target) {
                selectTab(i);
                return;
            }
        }
    }

    void onMediaFullscreen(GeckoSession target, boolean enabled) {
        if (target == session) setMediaFullscreen(enabled);
    }

    void prepareMediaPictureInPicture() {
        if (onWebPage()) sendCommand("mediaPip");
    }

    void requestMediaPictureInPicture(long width, long height) {
        if (android.os.Build.VERSION.SDK_INT < 26 || isInPictureInPictureMode()) return;
        try {
            long w = width > 0 ? width : 16;
            long h = height > 0 ? height : 9;
            double ratio = w / (double) h;
            if (!Double.isFinite(ratio) || ratio < 0.42 || ratio > 2.39) {
                w = 16;
                h = 9;
            }
            android.app.PictureInPictureParams.Builder builder =
                    new android.app.PictureInPictureParams.Builder()
                            .setAspectRatio(new android.util.Rational((int) Math.min(w, 10000),
                                                                     (int) Math.min(h, 10000)));
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                builder.setSeamlessResizeEnabled(true);
            }
            setBrowserChromeVisible(false);
            enterPictureInPictureMode(builder.build());
        } catch (Throwable e) {
            setBrowserChromeVisible(!mediaFullscreen);
            Toast.makeText(this, "Image dans l'image indisponible",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void setMediaFullscreen(boolean enabled) {
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
    }

    private void setBrowserChromeVisible(boolean visible) {
        android.view.View toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) toolbar.setVisibility(
                visible ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!visible && findBar != null) findBar.setVisibility(android.view.View.GONE);
        if (progress != null && !visible) progress.setVisibility(android.view.View.GONE);
    }

    private void exitMediaFullscreen() {
        if (onWebPage()) sendCommand("mediaExitFullscreen");
        setMediaFullscreen(false);
    }

'''

src = replace_once(
    src,
    """    // =======================================================================
    //  Menu
    // =======================================================================
""",
    bridge + """    // =======================================================================
    //  Menu
    // =======================================================================
""",
    "pont multimedia",
)

# Un onglet qui joue ne doit pas etre mis en veille par l'etape 5.
workspace_guard = """        if (tab.pinned || tab.priv || tab.sleeping) return;
"""
if workspace_guard in src:
    src = src.replace(
        workspace_guard,
        workspace_guard + "        if (mediaHub != null && mediaHub.isPlaying(tab.session)) return;\n",
        1,
    )

# Cycle de vie Android et synchronisation du compositeur Gecko avec le PiP.
src = replace_once(
    src,
    """    @Override
    protected void onPause() {""",
    """    @Override
    protected void onUserLeaveHint() {
        if (mediaHub != null) mediaHub.onUserLeaveHint();
        super.onUserLeaveHint();
    }

    @Override
    protected void onPause() {""",
    "depart vers PiP",
)

src = replace_once(
    src,
    """    @Override
    protected void onStop() {
        saveTabs();
        super.onStop();
    }

    // =======================================================================
    //  Schemas non web et selection de fichier""",
    """    @Override
    protected void onStop() {
        saveTabs();
        super.onStop();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip,
            android.content.res.Configuration configuration) {
        super.onPictureInPictureModeChanged(inPip, configuration);
        if (mediaHub != null) mediaHub.onPipModeChanged(inPip);
        setBrowserChromeVisible(!inPip && !mediaFullscreen);
    }

    @Override
    protected void onDestroy() {
        if (mediaHub != null) mediaHub.release();
        super.onDestroy();
    }

    // =======================================================================
    //  Schemas non web et selection de fichier""",
    "cycle de vie PiP",
)

# Le bouton retour sort d'abord du plein ecran.
src = replace_once(
    src,
    """    public void onBackPressed() {
        if (findBar != null && findBar.getVisibility() == android.view.View.VISIBLE) {""",
    """    public void onBackPressed() {
        if (mediaFullscreen) {
            exitMediaFullscreen();
            return;
        }
        if (findBar != null && findBar.getVisibility() == android.view.View.VISIBLE) {""",
    "retour plein ecran",
)

MAIN.write_text(src, encoding="utf-8")

# AndroidManifest : autorise le PiP et declare le recepteur des commandes.
manifest = ANDROID_MANIFEST.read_text(encoding="utf-8")
if 'android:supportsPictureInPicture="true"' not in manifest:
    manifest = replace_once(
        manifest,
        '            android:launchMode="singleTop"\n',
        '            android:launchMode="singleTop"\n'
        '            android:supportsPictureInPicture="true"\n',
        "attribut PiP",
    )
if 'android:name=".MediaActionReceiver"' not in manifest:
    manifest = replace_once(
        manifest,
        '        <receiver\n            android:name=".SearchWidget"',
        '        <receiver\n            android:name=".MediaActionReceiver"\n'
        '            android:exported="false" />\n\n'
        '        <receiver\n            android:name=".SearchWidget"',
        "recepteur multimedia",
    )
ANDROID_MANIFEST.write_text(manifest, encoding="utf-8")

# Extension : charge le petit script de preparation video.
try:
    data = json.loads(EXT_MANIFEST.read_text(encoding="utf-8"))
except Exception as exc:
    fail(f"manifest de l'extension illisible: {exc}")
content_scripts = data.setdefault("content_scripts", [])
if not any("media-tools.js" in entry.get("js", []) for entry in content_scripts):
    content_scripts.append({
        "matches": ["<all_urls>"],
        "js": ["media-tools.js"],
        "run_at": "document_idle",
        "all_frames": False,
    })
EXT_MANIFEST.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")

print("Centre multimedia installe dans MainActivity.java et les manifestes.")
print(f"Sauvegarde: {BACKUP}")
