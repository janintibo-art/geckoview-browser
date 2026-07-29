#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Verifie que les appels aux methodes declarees dans un fichier Java
correspondent au nombre d'arguments attendu.

check.py verifie qu'une methode citee existe, pas qu'elle est appelee
avec la bonne arite : addBookmark(titre, lien) passait le controle alors
que addBookmark() ne prend aucun argument. Ce script comble ce trou.

Les methodes surchargees acceptent l'ensemble des arites declarees.
"""
import re, sys, glob, collections

# Le modificateur d'acces est facultatif : les methodes de portee paquet
# (frequentes dans les classes imbriquees) doivent aussi etre reconnues.
DECL = re.compile(
    r'^\s*(?:(?:public|private|protected)\s+)?(?:static\s+|final\s+|synchronized\s+)*'
    r'[\w<>\[\],.?\s]+?\s+(\w+)\s*\(([^)]*)\)\s*(?:throws [\w,.\s]+)?\{', re.M)

def split_args(text):
    """Compte les arguments d'un appel en ignorant les virgules imbriquees."""
    depth = 0
    n = 0 if text.strip() == "" else 1
    i = 0
    while i < len(text):
        ch = text[i]
        # `<` et `>` ne sont PAS traites comme des chevrons ici : la fleche
        # des lambdas (`cat -> f(a, b)`) faussait la profondeur et comptait
        # les virgules internes comme des arguments.
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == '"':
            i += 1
            while i < len(text) and text[i] != '"':
                if text[i] == "\\": i += 1
                i += 1
        elif ch == "," and depth == 0:
            n += 1
        i += 1
    return n

def call_args(src, start):
    """Extrait le texte des arguments d'un appel ouvert a `start`."""
    depth = 0
    i = start
    while i < len(src):
        ch = src[i]
        if ch == '"':
            i += 1
            while i < len(src) and src[i] != '"':
                if src[i] == "\\": i += 1
                i += 1
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return src[start + 1:i]
        i += 1
    return None

problems = []
for path in sys.argv[1:] or glob.glob("app/src/main/java/**/*.java", recursive=True):
    src = open(path, encoding="utf-8", errors="replace").read()
    # Les commentaires sont blanchis, pas supprimes : les numeros de ligne
    # doivent rester ceux du fichier d'origine.
    def blank(m):
        return re.sub(r'[^\n]', ' ', m.group(0))
    clean = re.sub(r'//[^\n]*', blank, src)
    clean = re.sub(r'/\*.*?\*/', blank, clean, flags=re.S)

    # Mots-cles suivis d'une parenthese : ce ne sont pas des appels.
    KEYWORDS = {"for", "if", "while", "switch", "catch", "synchronized",
                "return", "new", "super", "this", "try"}

    arities = collections.defaultdict(set)
    variadic = set()
    for m in DECL.finditer(clean):
        params = m.group(2)
        n = split_args(params)
        arities[m.group(1)].add(n)
        # Varargs : tout appel d'au moins n-1 arguments est valide.
        if "..." in params:
            variadic.add(m.group(1))
            arities[m.group(1)].add(max(0, n - 1))

    for name, expected in arities.items():
        if name in KEYWORDS:
            continue
        # Un nom qui sert aussi ailleurs (interface, classe anonyme) est ignore
        for m in re.finditer(r'(?<![\w.])' + re.escape(name) + r'\s*\(', clean):
            # Ecarter la declaration elle-meme
            line_start = clean.rfind("\n", 0, m.start()) + 1
            line = clean[line_start:m.start()]
            if re.search(r'\b(public|private|protected)\b', line):
                continue
            args = call_args(clean, m.end() - 1)
            if args is None:
                continue
            got = split_args(args)
            if name in variadic:
                if got >= min(expected):
                    continue
            if got not in expected:
                ln = clean[:m.start()].count("\n") + 1
                problems.append(
                    f"{path}:{ln} {name}(...) appele avec {got} argument(s), "
                    f"attendu {sorted(expected)}")

if problems:
    print(f"{len(problems)} probleme(s) d'arite :\n")
    for p in problems:
        print("  -", p)
    sys.exit(1)
print("Arite des appels : rien a signaler.")
