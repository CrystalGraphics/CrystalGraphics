"""Which branch of every Stonecutter chain each node takes, and the nodes no branch covers.

    python singlejar-logic/directives.py runtime/mc/modern                  # chains some node falls through
    python singlejar-logic/directives.py runtime/mc/modern world/ mixin/    # and every chain in these paths

A tree is a directory holding Stonecutter branches (common, forge, ...), each with versions/<node>/ and src/. A
chain with no `else` that a node matches no branch of gives that node no code at all there: an import or a helper
only some versions need is fine, behaviour is a gap to decide on. Reads only `//? if`/`elif`/`else` with `>=`, `<`,
`>`, `<=` and exact versions, which is all the trees use.
"""
import os
import re
import sys


def ver(s):
    return tuple(int(p) for p in s.split("."))


def holds(pred, node):
    n = ver(node)
    for tok in pred.split():
        m = re.fullmatch(r"(>=|<=|>|<|=)?([0-9.]+)", tok)
        if not m:
            raise ValueError(f"predicate token {tok!r} in {pred!r}")
        op, v = m.group(1) or "=", ver(m.group(2))
        if not {">=": n >= v, "<": n < v, ">": n > v, "<=": n <= v, "=": n == v}[op]:
            return False
    return True


def chains(path):
    out, stack = [], []
    for no, raw in enumerate(open(path, encoding="utf-8", errors="replace"), 1):
        if "//?" not in raw:
            continue
        line = raw.strip()
        if m := re.search(r"//\?\s*if\s+(.+?)\s*\{\s*$", line):
            chain = {"line": no, "branches": [m.group(1)], "parents": [(c, len(c["branches"]) - 1) for c in stack]}
            stack.append(chain)
            out.append(chain)
        elif m := re.search(r"//\?\s*\}\s*elif\s+(.+?)\s*\{\s*$", line):
            stack[-1]["branches"].append(m.group(1))
        elif re.search(r"//\?\s*\}\s*else\s*\{\s*$", line):
            stack[-1]["branches"].append(None)
        elif re.search(r"//\?\s*\}\s*$", line):
            stack.pop()
    if stack:
        raise ValueError(f"{path}: {len(stack)} chain(s) left open")
    return out


def taken(chain, node):
    for i, pred in enumerate(chain["branches"]):
        if pred is None or holds(pred, node):
            return i
    return -1


def main(tree, show):
    gaps = 0
    for branch in sorted(os.listdir(tree)):
        versions, src = os.path.join(tree, branch, "versions"), os.path.join(tree, branch, "src")
        if not (os.path.isdir(versions) and os.path.isdir(src)):
            continue
        nodes = sorted(os.listdir(versions), key=ver)
        for dirpath, _, files in os.walk(src):
            for f in sorted(files):
                if not f.endswith(".java"):
                    continue
                path = os.path.join(dirpath, f)
                rel = os.path.relpath(path, tree).replace("\\", "/")
                for chain in chains(path):
                    by = {}
                    for node in nodes:
                        if all(taken(p, node) == i for p, i in chain["parents"]):
                            by.setdefault(taken(chain, node), []).append(node)
                    if -1 in by:
                        gaps += 1
                    if -1 in by or any(s in rel for s in show):
                        print(f"{rel}:{chain['line']}")
                        for i, pred in enumerate(chain["branches"]):
                            print(f"    {pred or 'else':>20}: {' '.join(by.get(i, [])) or '-'}")
                        if -1 in by:
                            print(f"    {'(no branch)':>20}: {' '.join(by[-1])}")
    print(f"chains a node falls through: {gaps}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2:])
