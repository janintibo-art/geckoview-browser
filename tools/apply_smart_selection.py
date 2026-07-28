#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ajoute la barre intelligente de selection a MainActivity.java.

Le script est idempotent et cree une sauvegarde avant la premiere modification.
"""

from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/example/geckobrowser/MainActivity.java"
BACKUP = MAIN.with_suffix(".java.before-smart-selection")
PASSATION = ROOT / "PASSATION.md"

SESSION_ANCHOR = "        session.setPromptDelegate(new Prompts(this, this::startFilePicker));"
SESSION_BLOCK = SESSION_ANCHOR + r'''
        session.setSelectionActionDelegate(new SmartSelectionDelegate(
                this,
                this::searchUrl,
                () -> sendCommand("translateSel"),
                () -> currentTitle,
                () -> currentUrl));'''

MENU_ANCHOR = r'''            .sub("\u2605", "Favoris", bookmarks().length() + " enregistre(s)",
                 this::showBookmarksMenu)'''
MENU_BLOCK = MENU_ANCHOR + r'''
            .sub("\u275D", "Citations",
                 SelectionNotebook.count(this) + " enregistree(s)",
                 () -> SelectionNotebook.show(this))'''

NOTE = """

## Barre intelligente de selection

La selection native GeckoView est enrichie avec Traduire, Rechercher, Lire,
Partager, Enregistrer et Markdown. Les actions sont masquees dans les champs de
mot de passe. Les citations restent locales dans les preferences Android et
sont accessibles depuis le menu principal.
"""


def fail(message: str) -> None:
    print("ERREUR : " + message)
    sys.exit(1)


def main() -> int:
    if not MAIN.exists():
        fail(f"MainActivity.java introuvable : {MAIN}")

    java_dir = MAIN.parent
    for required in ("SmartSelectionDelegate.java", "SelectionNotebook.java"):
        if not (java_dir / required).exists():
            fail(f"{required} absent. Decompressez le ZIP a la racine du depot.")

    source = MAIN.read_text(encoding="utf-8")
    original = source

    if "new SmartSelectionDelegate(" not in source:
        if SESSION_ANCHOR not in source:
            fail("point d'insertion du delegate de selection introuvable")
        source = source.replace(SESSION_ANCHOR, SESSION_BLOCK, 1)

    if "SelectionNotebook.count(this)" not in source:
        if MENU_ANCHOR not in source:
            fail("point d'insertion du menu Citations introuvable")
        source = source.replace(MENU_ANCHOR, MENU_BLOCK, 1)

    if source == original:
        print("Barre intelligente deja installee : aucune modification.")
    else:
        if not BACKUP.exists():
            shutil.copy2(MAIN, BACKUP)
            print(f"Sauvegarde : {BACKUP.relative_to(ROOT)}")
        MAIN.write_text(source, encoding="utf-8")
        print("MainActivity.java mis a jour.")

    if PASSATION.exists():
        text = PASSATION.read_text(encoding="utf-8")
        if "## Barre intelligente de selection" not in text:
            PASSATION.write_text(text.rstrip() + NOTE + "\n", encoding="utf-8")
            print("PASSATION.md complete.")

    # Verifications ciblees avant tools/check.py.
    final = MAIN.read_text(encoding="utf-8")
    checks = {
        "delegate": "session.setSelectionActionDelegate(new SmartSelectionDelegate(" in final,
        "traduction": 'sendCommand(\\"translateSel\\")' not in final and 'sendCommand("translateSel")' in final,
        "menu citations": "SelectionNotebook.count(this)" in final,
    }
    missing = [name for name, ok in checks.items() if not ok]
    if missing:
        fail("installation incomplete : " + ", ".join(missing))

    print("Barre intelligente installee. Lancez maintenant : python3 tools/check.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
