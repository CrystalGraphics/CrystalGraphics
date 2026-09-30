#!/usr/bin/env python3
"""
census_diff -- the GL state each client handed us, side by side, only where the clients disagree.

    python census_diff.py <instance-or-log> <instance-or-log> ...
    python census_diff.py "%APPDATA%/PrismLauncher/instances/Crystal 1211 Fabric" "…/Crystal 262 Fabric"
    python census_diff.py a.log b.log --all        # every value, not only the ones that differ

Reads the lines `-Dcrystalgraphics.host.census=true` logs (CgGlCensus): one per value, per entry point
(`opaque`, `transparent`, `gui`, `frame`). An instance directory means its newest of
.minecraft/logs/latest.log, debug.log and fml-client-latest.log. mcrender.py compares what Minecraft's
code says; this compares what a running client actually held.
"""

import argparse
import os
import re
import sys

LINE = re.compile(r"census (\S+) (\S+) = (.*)$")


def log_of(path):
    if os.path.isfile(path):
        return path
    logs = os.path.join(path, ".minecraft", "logs")
    candidates = [os.path.join(logs, n) for n in ("latest.log", "debug.log", "fml-client-latest.log")]
    candidates = [c for c in candidates if os.path.isfile(c)]
    if not candidates:
        sys.exit(f"no log under {path}")
    return max(candidates, key=os.path.getmtime)


def census(path):
    values = {}
    with open(log_of(path), encoding="utf-8", errors="replace") as f:
        for line in f:
            m = LINE.search(line.rstrip())
            if m:
                values[(m.group(1), m.group(2))] = m.group(3).strip()
    return values


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sources", nargs="+")
    ap.add_argument("--all", action="store_true", help="print every value, not only the differing ones")
    a = ap.parse_args()

    names = [os.path.basename(os.path.normpath(s)).replace("Crystal ", "") for s in a.sources]
    data = [census(s) for s in a.sources]
    for name, d in zip(names, data):
        if not d:
            print(f"# {name}: no census lines -- was -Dcrystalgraphics.host.census=true set?")
    keys = sorted(set().union(*data), key=lambda k: (k[0], k[1]))
    width = max([len(n) for n in names] + [8])
    label = None
    for key in keys:
        row = [d.get(key, "-") for d in data]
        if not a.all and len(set(row)) == 1:
            continue
        if key[0] != label:
            label = key[0]
            print(f"\n## {label}\n{'':34}" + "".join(n.ljust(width + 2) for n in names))
        print(f"{key[1]:34}" + "".join(v.ljust(width + 2) for v in row))


if __name__ == "__main__":
    main()
