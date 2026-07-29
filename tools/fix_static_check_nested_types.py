#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Corrige le faux positif ClassName.NestedType() dans tools/check.py."""

from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
CHECK = ROOT / "tools" / "check.py"
BACKUP = ROOT / "tools" / "check.py.before-nested-types-fix"
MARKER = "CHECK_NESTED_TYPES_V1"


def fail(message: str) -> None:
    print(f"ERREUR: {message}", file=sys.stderr)
    raise SystemExit(1)


if not CHECK.exists():
    fail("tools/check.py est introuvable. Lancez ce script depuis le depot GeckoBrowser.")

source = CHECK.read_text(encoding="utf-8")
if MARKER in source:
    print("Correction deja appliquee.")
    raise SystemExit(0)

old = '''        defs = set(re.findall(
            r"(?:public|private|protected|static)[\\w\\s<>\\[\\].]*?\\s(\\w+)\\s*\\(", src))
'''
new = '''        defs = set(re.findall(
            r"(?:public|private|protected|static)[\\w\\s<>\\[\\].]*?\\s(\\w+)\\s*\\(", src))
        # CHECK_NESTED_TYPES_V1 — ClassName.NestedType(...) est un constructeur,
        # pas un appel de methode statique. Le controleur doit donc reconnaitre
        # les types imbriques declares dans la classe cible.
        defs.update(re.findall(
            r"\\b(?:class|interface|enum|record)\\s+(\\w+)\\b", src))
'''

if old not in source:
    fail("La zone attendue dans tools/check.py a change; aucune modification effectuee.")

if not BACKUP.exists():
    shutil.copy2(CHECK, BACKUP)

CHECK.write_text(source.replace(old, new, 1), encoding="utf-8")
print("Correction appliquee a tools/check.py")
print(f"Sauvegarde : {BACKUP.relative_to(ROOT)}")
