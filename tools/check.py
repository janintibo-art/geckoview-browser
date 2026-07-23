#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check.py — controle statique du projet, execute avant la compilation.

Il verifie les erreurs qui ne se voient qu'a l'execution ou qui coutent un
aller-retour complet de compilation. Chaque famille correspond a une panne
reellement rencontree sur ce projet.
"""

import json
import os
import re
import subprocess
import sys
import xml.dom.minidom

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app", "src", "main")
EXT = os.path.join(MAIN, "assets", "adblock")
JAVA = os.path.join(MAIN, "java", "com", "example", "geckobrowser")
RES = os.path.join(MAIN, "res")

errors = []
warnings = []


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


# ---------------------------------------------------------------------------
def check_js_syntax():
    """Syntaxe de chaque fichier JavaScript."""
    if not shutil_which("node"):
        warn("node absent : syntaxe JavaScript non verifiee")
        return
    for name in sorted(os.listdir(EXT)):
        if not name.endswith(".js"):
            continue
        r = subprocess.run(["node", "--check", os.path.join(EXT, name)],
                           capture_output=True, text=True)
        if r.returncode != 0:
            first = (r.stderr.strip().splitlines() or ["erreur"])[-1]
            err("JS %s : %s" % (name, first))


def shutil_which(cmd):
    from shutil import which
    return which(cmd)


# ---------------------------------------------------------------------------
def check_manifest():
    """Manifeste de l'extension : validite et fichiers references."""
    path = os.path.join(EXT, "manifest.json")
    try:
        m = json.loads(read(path))
    except Exception as e:
        err("manifest.json illisible : %s" % e)
        return None

    refs = set(m.get("background", {}).get("scripts", []))
    for cs in m.get("content_scripts", []):
        refs |= set(cs.get("js", []))
    war = set(m.get("web_accessible_resources", []))
    refs |= {r for r in war if "*" not in r}

    for r in sorted(refs):
        if not os.path.exists(os.path.join(EXT, r)):
            err("manifest : fichier declare mais absent — %s" % r)

    # Sous-ressources des pages : sans declaration, elles ne se chargent pas
    for name in sorted(os.listdir(EXT)):
        if not name.endswith(".html"):
            continue
        html = read(os.path.join(EXT, name))
        subs = re.findall(r'src="([^"]+)"', html)
        subs += re.findall(r'href="([^"]+\.css)"', html)
        for sub in subs:
            if sub.startswith(("http", "data:", "#")):
                continue
            if not os.path.exists(os.path.join(EXT, sub)):
                err("%s reference %s, absent" % (name, sub))
            elif sub not in war:
                err("%s charge %s, non declare dans web_accessible_resources"
                    % (name, sub))

    # Scripts en ligne : interdits par la politique de securite de l'extension
    csp = m.get("content_security_policy", "")
    if "unsafe-inline" not in csp:
        for name in sorted(os.listdir(EXT)):
            if name.endswith(".html") and re.search(r"<script(?![^>]*\ssrc=)",
                                                    read(os.path.join(EXT, name))):
                err("%s contient un script en ligne, bloque par la politique"
                    % name)
    return m


# ---------------------------------------------------------------------------
def top_level_names(path):
    """Noms declares a la racine d'un fichier, donc partages."""
    src = read(path)
    return set(re.findall(
        r"^(?:const|let|var|function|async function)\s+([A-Za-z_$][\w$]*)",
        src, re.M))


def check_scope_collisions(m):
    """Collisions de portee : les scripts partagent un espace de noms."""
    if not m:
        return

    groups = {
        "scripts d'arriere-plan": m.get("background", {}).get("scripts", []),
        "scripts de contenu": [j for cs in m.get("content_scripts", [])
                               for j in cs.get("js", [])],
    }
    for label, files in groups.items():
        seen = {}
        for f in files:
            for n in top_level_names(os.path.join(EXT, f)):
                seen.setdefault(n, []).append(f)
        for n, fs in sorted(seen.items()):
            if len(fs) > 1:
                err("collision de portee (%s) : %s declare dans %s"
                    % (label, n, ", ".join(fs)))

    # Les pages HTML chargent plusieurs scripts dans la meme portee
    for name in sorted(os.listdir(EXT)):
        if not name.endswith(".html"):
            continue
        scripts = [s for s in re.findall(r'<script src="([^"]+)"',
                                         read(os.path.join(EXT, name)))
                   if s.endswith(".js")]
        seen = {}
        for f in scripts:
            p = os.path.join(EXT, f)
            if not os.path.exists(p):
                continue
            for n in top_level_names(p):
                seen.setdefault(n, []).append(f)
        for n, fs in sorted(seen.items()):
            if len(fs) > 1:
                err("collision de portee (%s) : %s dans %s"
                    % (name, n, ", ".join(fs)))


# ---------------------------------------------------------------------------
def check_java():
    """Equilibre des blocs et references de methodes."""
    if not os.path.isdir(JAVA):
        warn("sources Java introuvables")
        return

    all_src = ""
    for name in sorted(os.listdir(JAVA)):
        if not name.endswith(".java"):
            continue
        src = read(os.path.join(JAVA, name))
        all_src += src
        if src.count("{") != src.count("}"):
            err("%s : accolades desequilibrees (%+d)"
                % (name, src.count("{") - src.count("}")))
        if src.count("(") != src.count(")"):
            err("%s : parentheses desequilibrees (%+d)"
                % (name, src.count("(") - src.count(")")))

    # References de methode : this::nom
    for name in sorted(os.listdir(JAVA)):
        if not name.endswith(".java"):
            continue
        src = read(os.path.join(JAVA, name))
        defined = set(re.findall(
            r"(?:private|public|protected)\s+[\w<>\[\].]+\s+(\w+)\s*\(", src))
        for ref in set(re.findall(r"this::(\w+)", src)):
            if ref not in defined:
                err("%s : this::%s ne correspond a aucune methode"
                    % (name, ref))

    # Methodes statiques appelees sur les classes du projet
    classes = {n[:-5] for n in os.listdir(JAVA) if n.endswith(".java")}
    for cls in sorted(classes):
        src = read(os.path.join(JAVA, cls + ".java"))
        defs = set(re.findall(
            r"(?:public|private|protected|static)[\w\s<>\[\].]*?\s(\w+)\s*\(", src))
        for caller in sorted(classes):
            if caller == cls:
                continue
            calls = set(re.findall(r"\b" + cls + r"\.(\w+)\s*\(",
                                   read(os.path.join(JAVA, caller + ".java"))))
            for c in sorted(calls - defs):
                err("%s appelle %s.%s(), introuvable" % (caller, cls, c))


# ---------------------------------------------------------------------------
def check_resources():
    """Chaque R.type.nom cite doit exister."""
    if not os.path.isdir(RES):
        warn("dossier res introuvable")
        return

    have = {}
    for kind, folders in [("layout", ["layout"]),
                          ("xml", ["xml"]),
                          ("drawable", ["drawable", "drawable-nodpi"]),
                          ("mipmap", ["mipmap-xxhdpi", "mipmap-anydpi-v26"])]:
        names = set()
        for folder in folders:
            d = os.path.join(RES, folder)
            if os.path.isdir(d):
                names |= {os.path.splitext(f)[0] for f in os.listdir(d)}
        have[kind] = names

    for kind, fname in [("string", "strings.xml"), ("color", "colors.xml"),
                        ("style", "styles.xml")]:
        p = os.path.join(RES, "values", fname)
        have[kind] = set(re.findall(r'name="([^"]+)"', read(p))) \
            if os.path.exists(p) else set()

    ids = set()
    d = os.path.join(RES, "layout")
    if os.path.isdir(d):
        for f in os.listdir(d):
            ids |= set(re.findall(r"@\+id/(\w+)", read(os.path.join(d, f))))
    have["id"] = ids

    java_src = ""
    if os.path.isdir(JAVA):
        for f in os.listdir(JAVA):
            if f.endswith(".java"):
                java_src += read(os.path.join(JAVA, f))

    # Les ressources du systeme (android.R.*) ne sont pas dans notre projet :
    # sans cette exclusion, chaque icone standard serait signalee a tort.
    cited = re.findall(r"(?<!android\.)\bR\.(\w+)\.(\w+)", java_src)
    for kind, name in sorted(set(cited)):
        if kind in have and name not in have[kind]:
            err("ressource citee mais absente : R.%s.%s" % (kind, name))

    # Ressources citees depuis les fichiers XML
    for folder in ("layout", "xml", "drawable"):
        d = os.path.join(RES, folder)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            src = read(os.path.join(d, f))
            for kind, name in re.findall(r"@(string|color|drawable|style|mipmap)/(\w+)", src):
                if kind in have and name not in have[kind]:
                    err("%s/%s cite @%s/%s, absent" % (folder, f, kind, name))


# ---------------------------------------------------------------------------
def check_xml():
    """Bonne formation de tous les fichiers XML."""
    targets = [os.path.join(MAIN, "AndroidManifest.xml")]
    for folder in ("layout", "values", "xml", "drawable"):
        d = os.path.join(RES, folder)
        if os.path.isdir(d):
            targets += [os.path.join(d, f) for f in os.listdir(d)
                        if f.endswith(".xml")]
    for t in targets:
        if not os.path.exists(t):
            continue
        try:
            xml.dom.minidom.parse(t)
        except Exception as e:
            err("XML mal forme %s : %s" % (os.path.relpath(t, ROOT), e))


# ---------------------------------------------------------------------------
def check_manifest_android():
    """Classes declarees dans le manifeste Android."""
    p = os.path.join(MAIN, "AndroidManifest.xml")
    if not os.path.exists(p):
        return
    src = read(p)
    for cls in re.findall(r'android:name="\.(\w+)"', src):
        if not os.path.exists(os.path.join(JAVA, cls + ".java")):
            err("AndroidManifest declare .%s, classe absente" % cls)


# ---------------------------------------------------------------------------
def main():
    check_js_syntax()
    m = check_manifest()
    check_scope_collisions(m)
    check_java()
    check_resources()
    check_xml()
    check_manifest_android()

    for w in warnings:
        print("  avertissement : %s" % w)

    if errors:
        print("\n%d probleme(s) detecte(s) :\n" % len(errors))
        for e in errors:
            print("  - %s" % e)
        return 1

    print("Controle statique : rien a signaler.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
