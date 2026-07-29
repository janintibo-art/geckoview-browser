import sys
def scan(path):
    s = open(path, encoding="utf-8").read()
    i = 0; n = len(s); depth = 0; par = 0; line = 1; mind = 0
    while i < n:
        ch = s[i]
        if ch == "\n": line += 1; i += 1; continue
        if ch == "/" and i+1 < n and s[i+1] == "/":
            while i < n and s[i] != "\n": i += 1
            continue
        if ch == "/" and i+1 < n and s[i+1] == "*":
            i += 2
            while i+1 < n and not (s[i] == "*" and s[i+1] == "/"):
                if s[i] == "\n": line += 1
                i += 1
            i += 2; continue
        if ch == '"':
            i += 1
            while i < n and s[i] != '"':
                if s[i] == "\\": i += 1
                i += 1
            i += 1; continue
        if ch == "'":
            i += 1
            while i < n and s[i] != "'":
                if s[i] == "\\": i += 1
                i += 1
            i += 1; continue
        if ch == "{": depth += 1
        elif ch == "}":
            depth -= 1
            if depth < mind: mind = depth
        elif ch == "(": par += 1
        elif ch == ")": par -= 1
        i += 1
    return depth, par, mind
for p in sys.argv[1:]:
    d, pa, m = scan(p)
    print(f"{p}: accolades={d} parentheses={pa} min={m} " + ("OK" if d==0 and pa==0 and m==0 else "*** DESEQUILIBRE ***"))
