#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ajoute le coffre chiffre de mots de passe a GeckoBrowser."""

from pathlib import Path
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/example/geckobrowser/MainActivity.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
BACKUP_MAIN = MAIN.with_name("MainActivity.java.before-password-vault")
BACKUP_MANIFEST = MANIFEST.with_name("AndroidManifest.xml.before-password-vault")
MARKER = "PASSWORD_VAULT_V1"


def fail(message: str) -> None:
    print(f"ERREUR: {message}", file=sys.stderr)
    sys.exit(1)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"ancre {label!r} trouvee {count} fois au lieu d'une")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    result, count = re.subn(pattern, replacement, text, count=1, flags=re.M)
    if count != 1:
        fail(f"ancre {label!r} trouvee {count} fois au lieu d'une")
    return result


for path in (MAIN, MANIFEST):
    if not path.is_file():
        fail(f"fichier introuvable: {path}")

for path in (
    ROOT / "app/src/main/java/com/example/geckobrowser/PasswordVault.java",
    ROOT / "app/src/main/java/com/example/geckobrowser/VaultPrompts.java",
    ROOT / "app/src/main/res/xml/backup_rules.xml",
    ROOT / "app/src/main/res/xml/data_extraction_rules.xml",
):
    if not path.is_file():
        fail(f"fichier de l'overlay absent: {path}")

src = MAIN.read_text(encoding="utf-8")
if MARKER in src:
    print("Coffre de mots de passe deja installe.")
    sys.exit(0)

# Le coffre reste compatible avec la branche actuellement publiee, que les
# overlays optionnels Onglets avances / Telechargements / Extensions aient ete
# appliques ou qu'ils soient seulement presents dans le depot.
for required in ("MEDIA_HUB_V1", "SmartSelectionDelegate", "SessionStore", "PrivacyCockpit"):
    if required not in src:
        fail(f"la version attendue de MainActivity.java manque {required}")

if not BACKUP_MAIN.exists():
    shutil.copy2(MAIN, BACKUP_MAIN)
if not BACKUP_MANIFEST.exists():
    shutil.copy2(MANIFEST, BACKUP_MANIFEST)

# Champ, place a cote du centre multimedia. Cette ancre est commune aux
# variantes avec ou sans gestionnaire d'extensions.
src = replace_once(
    src,
    """    // MEDIA_HUB_V1 — lecture native, notification, plein ecran et PiP.
    private MediaHub mediaHub;
    private boolean mediaFullscreen = false;
""",
    """    // MEDIA_HUB_V1 — lecture native, notification, plein ecran et PiP.
    private MediaHub mediaHub;
    private boolean mediaFullscreen = false;

    // PASSWORD_VAULT_V1 — coffre chiffre et remplissage GeckoView.
    private PasswordVault passwordVault;
""",
    "champ PasswordVault",
)

# Le delegate de stockage est attache au runtime juste apres sa creation, avant
# l'ouverture des sessions. Fonctionne aussi si d'autres centres sont initialises
# entre ce bloc et installBlocker().
src = replace_once(
    src,
    """        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(this, buildSettings());
        }
""",
    """        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(this, buildSettings());
        }
        passwordVault = PasswordVault.get(this);
        sRuntime.setAutocompleteStorageDelegate(passwordVault);
        try {
            sRuntime.getSettings().setLoginAutofillEnabled(
                    passwordVault.isAutofillEnabled());
        } catch (Throwable ignored) { }
""",
    "initialisation du coffre",
)

src = replace_once(
    src,
    "        session.setPromptDelegate(new Prompts(this, this::startFilePicker));",
    """        session.setPromptDelegate(new VaultPrompts(
                this, this::startFilePicker, passwordVault,
                this::isPrivateSession));""",
    "delegue des invites",
)

# Router d'abord les deux activites du coffre (deverrouillage et import), sans
# supposer la presence du selecteur XPI ajoute par l'etape 8.
src = replace_once(
    src,
    """    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
""",
    """    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (passwordVault != null
                && passwordVault.onActivityResult(this, requestCode, resultCode, data)) {
            return;
        }
""",
    "resultat du coffre",
)

# Ajouter le nettoyage au debut du onDestroy existant, quel que soit le nombre
# de centres natifs deja branches.
src = regex_once(
    src,
    r"(    protected void onDestroy\(\) \{\n)(?!        if \(passwordVault)",
    r"\1        if (passwordVault != null) passwordVault.onActivityDestroyed(this);\n",
    "nettoyage du coffre",
)

src = replace_once(
    src,
    """    private void exitMediaFullscreen() {
        if (onWebPage()) sendCommand("mediaExitFullscreen");
        setMediaFullscreen(false);
    }

    // =======================================================================
    //  Menu""",
    """    private void exitMediaFullscreen() {
        if (onWebPage()) sendCommand("mediaExitFullscreen");
        setMediaFullscreen(false);
    }

    // =======================================================================
    //  Coffre de mots de passe
    // =======================================================================
    private boolean isPrivateSession(GeckoSession target) {
        if (target == null) return false;
        for (Tab tab : tabs) {
            if (tab.session == target) return tab.priv;
        }
        return target == session && privateMode;
    }

    private void showPasswordVault() {
        passwordVault.show(this, this::showMenu, enabled -> {
            try { sRuntime.getSettings().setLoginAutofillEnabled(enabled); }
            catch (Throwable ignored) { }
        });
    }

    // =======================================================================
    //  Menu""",
    "methodes du coffre",
)

# Placement pres des donnees personnelles, sans modifier les ancres utilisees
# par les overlays Telechargements et Extensions.
src = replace_once(
    src,
    r'''            .sub("\u275D", "Citations",
                 SelectionNotebook.count(this) + " enregistree(s)",
                 () -> SelectionNotebook.show(this))
            .sub("\u267B", "Corbeille", null, this::showTrash)''',
    r'''            .sub("\u275D", "Citations",
                 SelectionNotebook.count(this) + " enregistree(s)",
                 () -> SelectionNotebook.show(this))
            .sub("\u25C9", "Mots de passe", passwordVault.summary(),
                 this::showPasswordVault)
            .sub("\u267B", "Corbeille", null, this::showTrash)''',
    "menu mots de passe",
)

src = replace_once(
    src,
    """        GeckoRuntimeSettings.Builder b = new GeckoRuntimeSettings.Builder()
                .contentBlocking(blocking)
                .javaScriptEnabled(true);""",
    """        GeckoRuntimeSettings.Builder b = new GeckoRuntimeSettings.Builder()
                .contentBlocking(blocking)
                .javaScriptEnabled(true)
                .loginAutofillEnabled(
                        prefs.getBoolean("passwordAutofill", true));""",
    "reglage de remplissage",
)

MAIN.write_text(src, encoding="utf-8")

manifest = MANIFEST.read_text(encoding="utf-8")
if "@xml/backup_rules" not in manifest:
    pattern = r'(android:allowBackup="true")'
    replacement = (r'\1\n'
                   r'        android:fullBackupContent="@xml/backup_rules"\n'
                   r'        android:dataExtractionRules="@xml/data_extraction_rules"')
    manifest, count = re.subn(pattern, replacement, manifest, count=1)
    if count != 1:
        fail("attribut android:allowBackup introuvable dans le manifeste")
MANIFEST.write_text(manifest, encoding="utf-8")

print("Coffre chiffre de mots de passe installe.")
print(f"Sauvegardes : {BACKUP_MAIN} et {BACKUP_MANIFEST}")
