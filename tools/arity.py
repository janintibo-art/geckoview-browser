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
# Motif borne volontairement : la version precedente autorisait espaces et
# sauts de ligne dans le type de retour ([\w<>...\s]+?), ce qui provoquait un
# retour arriere catastrophique — 35 s sur ce projet, des minutes sur un
# telephone. Chaque partie est desormais limitee et sans \s libre.
DECL = re.compile(
    r'^[ \t]*'
    r'(?:(?:public|private|protected)[ \t]+)?'
    r'(?:(?:static|final|synchronized|abstract|native|default)[ \t]+){0,3}'
    r'(?:<[^<>{}\n]{0,80}>[ \t]*)?'                 # methode generique
    r'[\w.$]+(?:<[^<>{}\n]{0,120}>)?(?:\[\])*[ \t]+'  # type de retour
    r'(\w+)[ \t]*\(([^)]{0,800})\)[ \t]*'
    r'(?:throws[ \t][\w,.\s]{0,150})?\{', re.M)

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

    # Un seul balayage pour tous les appels, au lieu d'un balayage complet
    # par nom de methode : sur un fichier de 170 Ko avec ~300 methodes, la
    # version naive prenait plus de 30 s (des minutes sur un telephone).
    line_of = None
    for m in re.finditer(r'(?<![\w.])(\w+)\s*\(', clean):
        name = m.group(1)
        expected = arities.get(name)
        if expected is None or name in KEYWORDS:
            continue
        # Ecarter la declaration elle-meme
        line_start = clean.rfind("\n", 0, m.start()) + 1
        line = clean[line_start:m.start()]
        if re.search(r'\b(public|private|protected|static)\b', line):
            continue
        args = call_args(clean, m.end() - 1)
        if args is None:
            continue
        got = split_args(args)
        if name in variadic and got >= min(expected):
            continue
        if got not in expected:
            if line_of is None:
                line_of = [0] * (len(clean) + 1)
                n = 1
                for i, ch in enumerate(clean):
                    line_of[i] = n
                    if ch == "\n":
                        n += 1
                line_of[len(clean)] = n
            problems.append(
                f"{path}:{line_of[m.start()]} {name}(...) appele avec "
                f"{got} argument(s), attendu {sorted(expected)}")

if problems:
    print(f"{len(problems)} probleme(s) d'arite :\n")
    for p in problems:
        print("  -", p)
    sys.exit(1)
print("Arite des appels : rien a signaler.")
