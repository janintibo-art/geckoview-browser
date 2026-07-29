#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/example/geckobrowser"
MAIN = JAVA / "MainActivity.java"
MANAGER = JAVA / "EncryptedSyncManager.java"
CHECK = ROOT / "tools/check.py"
BACKUP = MAIN.with_name("MainActivity.java.before-encrypted-sync")
MARKER = "ENCRYPTED_SYNC_V1"


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
    fail(f"classe introuvable: {MANAGER}")

source = MAIN.read_text(encoding="utf-8")
if MARKER in source:
    print("Synchronisation chiffree deja installee — aucune modification.")
    sys.exit(0)

for required in ("SPLIT_SCREEN_V1", "WEB_APPS_V1", "PASSWORD_VAULT_V1"):
    if required not in source:
        fail(f"l'etape requise {required} n'est pas presente")

if CHECK.is_file() and "CHECK_NESTED_TYPES_V1" not in CHECK.read_text(encoding="utf-8"):
    fail("appliquez d'abord le correctif du controle statique des classes imbriquees")

if not BACKUP.exists():
    shutil.copy2(MAIN, BACKUP)

source = replace_once(
    source,
    '''    // SPLIT_SCREEN_V1 — deux onglets visibles et redimensionnables.\n    private SplitScreenManager splitScreen;\n\n    private static final int REQ_FILE = 8123;''',
    '''    // SPLIT_SCREEN_V1 — deux onglets visibles et redimensionnables.\n    private SplitScreenManager splitScreen;\n\n    // ENCRYPTED_SYNC_V1 — paquet chiffre partage via le selecteur Android.\n    private EncryptedSyncManager encryptedSync;\n\n    private static final int REQ_FILE = 8123;''',
    "champ du gestionnaire",
)

source = replace_once(
    source,
    '''                });\n        installBlocker();''',
    '''                });\n        encryptedSync = new EncryptedSyncManager(this, this::flushAndSaveTabs);\n        installBlocker();''',
    "initialisation",
)

source = replace_once(
    source,
    '''    protected void onPause() {\n        flushAndSaveTabs();\n        super.onPause();\n    }''',
    '''    protected void onPause() {\n        flushAndSaveTabs();\n        if (encryptedSync != null) encryptedSync.onPause();\n        super.onPause();\n    }''',
    "sauvegarde automatique",
)

source = replace_once(
    source,
    '''    protected void onDestroy() {\n        if (passwordVault != null) passwordVault.onActivityDestroyed(this);\n        if (splitScreen != null) splitScreen.release();''',
    '''    protected void onDestroy() {\n        if (passwordVault != null) passwordVault.onActivityDestroyed(this);\n        if (encryptedSync != null) encryptedSync.release();\n        if (splitScreen != null) splitScreen.release();''',
    "liberation",
)

source = replace_once(
    source,
    '''    protected void onActivityResult(int requestCode, int resultCode, Intent data) {\n        super.onActivityResult(requestCode, resultCode, data);\n        if (passwordVault != null''',
    '''    protected void onActivityResult(int requestCode, int resultCode, Intent data) {\n        super.onActivityResult(requestCode, resultCode, data);\n        if (encryptedSync != null\n                && encryptedSync.onActivityResult(requestCode, resultCode, data)) {\n            return;\n        }\n        if (passwordVault != null''',
    "retour du selecteur de fichier",
)

source = replace_once(
    source,
    '''    // =======================================================================\n    //  Coffre de mots de passe\n    // =======================================================================''',
    '''    // =======================================================================\n    //  Synchronisation chiffree\n    // =======================================================================\n    private void showEncryptedSync() {\n        encryptedSync.show(this::showMenu,\n                () -> session.loadUri(extPage("sync.html")));\n    }\n\n    // =======================================================================\n    //  Coffre de mots de passe\n    // =======================================================================''',
    "methode du menu",
)

source = replace_once(
    source,
    '''            .add("\\u21C4", "Synchronisation", () -> session.loadUri(extPage("sync.html")))''',
    '''            .sub("\\u21C4", "Synchronisation chiffree", encryptedSync.summary(),\n                 this::showEncryptedSync)''',
    "entree de menu",
)

if source.count("{") != source.count("}"):
    fail("accolades desequilibrees apres modification")
if source.count("(") != source.count(")"):
    fail("parentheses desequilibrees apres modification")

MAIN.write_text(source, encoding="utf-8")
print("Synchronisation chiffree installee.")
print(f"Sauvegarde: {BACKUP.relative_to(ROOT)}")
