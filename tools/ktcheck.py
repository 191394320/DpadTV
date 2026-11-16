"""Delimiter scanner for the Kotlin sources -- this machine has no JDK, so a hand edit that
loses a brace has to be caught before it costs a CI round.

Stack based, and it actually understands Kotlin lexical states: line comments, block comments,
ordinary strings with escapes, triple-quoted raw strings, char literals, and `${ ... }` string
templates (which can nest another string inside them). An earlier version just stripped patterns
with regexes and reported MainActivity/Prefs as unbalanced -- every hit was an apostrophe or a
`'('` char literal, which made the script worse than useless.

Run from the repo root:  python tools/ktcheck.py [file ...]
"""
import glob
import sys

OPEN = {"{": "}", "(": ")", "[": "]"}
CLOSE = {"}": "{", ")": "(", "]": "["}


def scan(text):
    """returns (list_of_problems, line_of_last_state)"""
    problems = []
    stack = []          # (delimiter, line, col, kind) kind: 'code' or 'template'
    line, col = 1, 0
    i, n = 0, len(text)
    state = "code"
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
            col = 0
            if state == "line_comment":
                state = "code"
            i += 1
            continue
        col += 1
        if state == "line_comment":
            i += 1
            continue
        if state == "block_comment":
            if c == "*" and nxt == "/":
                state, i, col = "code", i + 2, col + 1
                continue
            i += 1
            continue
        if state == "char":
            if c == "\\":
                i += 2
                col += 1
                continue
            if c == "'":
                state = "code"
            i += 1
            continue
        if state == "raw":
            if c == '"' and text[i:i + 3] == '"""':
                state, i, col = "code", i + 3, col + 2
                continue
            if c == "$" and nxt == "{":
                stack.append(("{", line, col, "template"))
                state, i, col = "code", i + 2, col + 1
                continue
            i += 1
            continue
        if state == "string":
            if c == "\\":
                i += 2
                col += 1
                continue
            if c == '"':
                state = "code"
                i += 1
                continue
            if c == "$" and nxt == "{":
                stack.append(("{", line, col, "template"))
                state, i, col = "code", i + 2, col + 1
                continue
            i += 1
            continue
        # state == code
        if c == "/" and nxt == "/":
            state, i = "line_comment", i + 2
            col += 1
            continue
        if c == "/" and nxt == "*":
            state, i = "block_comment", i + 2
            col += 1
            continue
        if c == '"' and text[i:i + 3] == '"""':
            state, i, col = "raw", i + 3, col + 2
            continue
        if c == '"':
            state, i = "string", i + 1
            continue
        if c == "'":
            state, i = "char", i + 1
            continue
        if c in OPEN:
            stack.append((c, line, col, "code"))
            i += 1
            continue
        if c in CLOSE:
            if not stack:
                problems.append("line %d col %d: stray '%s'" % (line, col, c))
            else:
                o, ol, oc, kind = stack[-1]
                if o != CLOSE[c]:
                    problems.append(
                        "line %d col %d: '%s' closes '%s' opened at line %d col %d"
                        % (line, col, c, o, ol, oc)
                    )
                stack.pop()
                if kind == "template":
                    state = "string"
            i += 1
            continue
        i += 1
    for o, ol, oc, kind in stack:
        if kind == "code":
            problems.append("line %d col %d: unclosed '%s'" % (ol, oc, o))
    return problems


def main():
    files = sys.argv[1:] or sorted(glob.glob("app/src/main/java/com/dpadtv/client/*.kt"))
    if not files:
        print("nothing to check; run from the repo root or pass file paths")
        return 1
    total = 0
    for f in files:
        p = scan(open(f, encoding="utf-8").read())
        total += len(p)
        for x in p:
            print("FAIL %s: %s" % (f, x))
    print("%d files checked, %d problems" % (len(files), total))
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
