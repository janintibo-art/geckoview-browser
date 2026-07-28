#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ajoute le gestionnaire natif de WebExtensions a GeckoBrowser."""

from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/geckobrowser"
MAIN = JAVA / "MainActivity.java"
MANAGER = JAVA / "ExtensionManager.java"
BACKUP = MAIN.with_name("MainActivity.java.before-extension-manager")
MARKER = "EXTENSION_MANAGER_V1"


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
    fail(f"fichier introuvable dans l'overlay: {MANAGER}")

src = MAIN.read_text(encoding="utf-8")
if MARKER in src:
    print("Gestionnaire d'extensions deja installe.")
    sys.exit(0)

for required in ("DOWNLOAD_CENTER_V1", "MEDIA_HUB_V1", "TAB_WORKSPACE_V1",
                 "SmartSelectionDelegate"):
    if required not in src:
        fail(f"MainActivity.java ne contient pas la version attendue: {required}")

if not BACKUP.exists():
    shutil.copy2(MAIN, BACKUP)

# ---------------------------------------------------------------------------
# Etat du gestionnaire et code de selection du paquet XPI.
# ---------------------------------------------------------------------------
src = replace_once(
    src,
    '''    // MEDIA_HUB_V1 — lecture native, notification, plein ecran et PiP.
    private MediaHub mediaHub;
    private boolean mediaFullscreen = false;

    private static final int REQ_FILE = 8123;''',
    '''    // MEDIA_HUB_V1 — lecture native, notification, plein ecran et PiP.
    private MediaHub mediaHub;
    private boolean mediaFullscreen = false;

    // EXTENSION_MANAGER_V1 — installation, permissions et actions WebExtension.
    private ExtensionManager extensionManager;

    private static final int REQ_FILE = 8123;
    private static final int REQ_EXTENSION = 8124;''',
    "champs du gestionnaire",
)

# Initialisation apres creation du runtime, puis avant les premieres sessions.
src = replace_once(
    src,
    '''        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(this, buildSettings());
        }
        installBlocker();''',
    '''        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(this, buildSettings());
        }
        extensionManager = new ExtensionManager(
                this, sRuntime, EXT_ID,
                url -> {
                    setupSession(false, url);
                    selectTab(tabs.size() - 1);
                },
                this::pickExtensionPackage);
        installBlocker();''',
    "initialisation",
)

# Chaque onglet fournit ses actions de navigateur et de page aux extensions.
src = replace_once(
    src,
    '''        PrivacyCockpit.attach(tab.session);
        mediaHub.attach(tab.session);''',
    '''        PrivacyCockpit.attach(tab.session);
        mediaHub.attach(tab.session);
        extensionManager.attachSession(tab.session);''',
    "liaison aux sessions",
)

# Signaler l'onglet actif au controleur WebExtension.
src = replace_once(
    src,
    '''                s.setActive(i == active);
                s.setFocused(i == active);''',
    '''                s.setActive(i == active);
                s.setFocused(i == active);
                extensionManager.setTabActive(s, i == active);''',
    "onglet actif",
)

# Les paquets XPI recus directement depuis le web ouvrent l'installation au
# lieu d'etre simplement ranges dans Telechargements.
src = replace_once(
    src,
    '''            public void onExternalResponse(GeckoSession s, WebResponse response) {
                Downloads.save(MainActivity.this, response);
            }''',
    '''            public void onExternalResponse(GeckoSession s, WebResponse response) {
                if (extensionManager != null
                        && extensionManager.handleExternalResponse(response)) return;
                Downloads.save(MainActivity.this, response);
            }''',
    "reponse XPI",
)

# ---------------------------------------------------------------------------
# Selecteur Android de fichier XPI et routage de son resultat.
# ---------------------------------------------------------------------------
src = replace_once(
    src,
    '''    private void startFilePicker(GeckoSession.PromptDelegate.FilePrompt prompt,
                                 GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result) {''',
    '''    private void pickExtensionPackage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-xpinstall");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/x-xpinstall", "application/zip",
                "application/octet-stream"
        });
        try {
            startActivityForResult(Intent.createChooser(intent,
                    "Choisir une extension XPI"), REQ_EXTENSION);
        } catch (Exception error) {
            Toast.makeText(this, "Aucun selecteur de fichier disponible",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startFilePicker(GeckoSession.PromptDelegate.FilePrompt prompt,
                                 GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result) {''',
    "selecteur XPI",
)

src = replace_once(
    src,
    '''        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || pendingFile == null) return;''',
    '''        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXTENSION) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && extensionManager != null) {
                extensionManager.installFromContentUri(data.getData());
            }
            return;
        }
        if (requestCode != REQ_FILE || pendingFile == null) return;''',
    "resultat XPI",
)

# ---------------------------------------------------------------------------
# Entree du menu, juste apres le centre de telechargements de l'etape 7.
# ---------------------------------------------------------------------------
src = replace_once(
    src,
    '''            .sub("\\u21E9", "Telechargements", DownloadCenter.summary(this),
                 () -> DownloadCenter.show(this, this::showMenu))
            .sub("\\u25A4", "Page", pageHost(), this::showPageMenu)''',
    '''            .sub("\\u21E9", "Telechargements", DownloadCenter.summary(this),
                 () -> DownloadCenter.show(this, this::showMenu))
            .sub("\\u229E", "Extensions", extensionManager.summary(),
                 () -> extensionManager.show(this::showMenu))
            .sub("\\u25A4", "Page", pageHost(), this::showPageMenu)''',
    "menu principal",
)

# Liberer le dialogue popup et le delegate lors de la destruction.
src = replace_once(
    src,
    '''    protected void onDestroy() {
        if (mediaHub != null) mediaHub.release();
        super.onDestroy();
    }''',
    '''    protected void onDestroy() {
        if (extensionManager != null) extensionManager.release();
        if (mediaHub != null) mediaHub.release();
        super.onDestroy();
    }''',
    "liberation",
)

MAIN.write_text(src, encoding="utf-8")
print("Gestionnaire d'extensions installe.")
print(f"Sauvegarde : {BACKUP}")
