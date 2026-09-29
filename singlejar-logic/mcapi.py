#!/usr/bin/env python3
"""
mcapi -- the Minecraft and loader API of every node, from stubs.zip, without setting up any of them.

    python mcapi.py PlayerList isOp            # every isOp on PlayerList, and on which nodes it exists
    python mcapi.py PlayerList                 # the whole class, member by member
    python mcapi.py RegisterCommandsEvent      # loader API too: Forge, NeoForge, Fabric API, Mixin, ...
    python mcapi.py find 'CommandRegistrationCallback|RegisterCommandsEvent'   # classes by regex
    python mcapi.py find 'Menu' --in net/minecraft/world/inventory              # scoped to a package
    python mcapi.py nodes                      # every node the database holds

A class may be named fully (`net/minecraft/server/players/PlayerList`, dots work too) or by its simple
name; a simple name that matches several classes lists them. The answer is grouped into version runs
per branch, e.g. `forge:1.13.2-1.21.8`, so one line says where a spelling holds and where it breaks.

Names are what each node compiles against: Mojang's on the modern tree (1.13.2 and 1.14.3 use
names carried back from 1.14.4), MCP's on the legacy tree. 1.7.10 is not in the database -- read its
decompiled sources under runtime/mc/1710/build/rfg/ after a real build.
"""

import argparse
import os
import re
import sys
import zipfile

ZIP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stubs.zip")


class Stubs:
    def __init__(self, path):
        self.zip = zipfile.ZipFile(path)
        self.nodes = [n for n in self.zip.read("nodes.txt").decode().splitlines() if n.strip()]
        self.sets = {}
        for line in self.zip.read("sets.txt").decode().splitlines():
            if line.strip():
                sid, runs = line.split(" ", 1)
                self.sets[sid] = frozenset(self._expand(runs))

    def _expand(self, runs):
        out = []
        for run in runs.split(","):
            branch, _, versions = run.rpartition(":")
            first, _, last = versions.partition("-")
            start = self.nodes.index(f"{branch}:{first}")
            end = self.nodes.index(f"{branch}:{last}") if last else start
            out.extend(range(start, end + 1))
        return out

    def runs(self, indices):
        """Node indices as runs of consecutive nodes of one branch: `forge:1.13.2-1.21.8,fabric:...`."""
        parts, ordered = [], sorted(indices)
        i = 0
        while i < len(ordered):
            j = i
            branch = self.nodes[ordered[i]].split(":")[0]
            while (j + 1 < len(ordered) and ordered[j + 1] == ordered[j] + 1
                   and self.nodes[ordered[j + 1]].split(":")[0] == branch):
                j += 1
            a, b = self.nodes[ordered[i]], self.nodes[ordered[j]]
            parts.append(a if i == j else f"{a}-{b.split(':', 1)[1]}")
            i = j + 1
        return ", ".join(parts)

    @staticmethod
    def entry_of(owner):
        packages = owner.split("/")[:-1]
        depth = 3 if owner.startswith("net/minecraft/") else 2
        return "api/" + (".".join(packages[:depth]) or "default") + ".sig"

    def entries(self):
        return [n for n in self.zip.namelist() if n.startswith("api/")]

    def blocks(self, entry):
        """Yields (set id, class line, member lines) for every class block in one entry."""
        with self.zip.open(entry) as raw:
            current, head, members = None, None, []
            for line in (l.decode("utf-8", "replace").rstrip("\n") for l in raw):
                if line.startswith("in "):
                    if head:
                        yield current, head, members
                    current, head, members = line[3:], None, []
                elif line.startswith("class "):
                    if head:
                        yield current, head, members
                    head, members = line, []
                elif head is not None:
                    members.append(line)
            if head:
                yield current, head, members

    def find_classes(self, pattern, scope=None):
        rx = re.compile(pattern)
        found = set()
        entries = self.entries()
        if scope:
            entries = [self.entry_of(scope.rstrip("/") + "/X")] if "/" in scope else entries
        for entry in entries:
            if entry not in self.zip.namelist():
                continue
            for _, head, _ in self.blocks(entry):
                name = head.split(" ")[1]
                if (not scope or name.startswith(scope)) and rx.search(name):
                    found.add(name)
        return sorted(found)

    def resolve(self, name):
        name = name.replace(".", "/")
        if "/" in name:
            return [name]
        return self.find_classes(r"(^|/|\$)" + re.escape(name) + r"$")


TYPE = re.compile(r"\[*(?:L[^;]+;|[BCDFIJSZV])")
PRIM = {"B": "byte", "C": "char", "D": "double", "F": "float", "I": "int", "J": "long", "S": "short",
        "Z": "boolean", "V": "void"}


def pretty(desc):
    """`(Lcom/mojang/authlib/GameProfile;)Z` -> `(GameProfile) boolean`."""
    def one(t):
        dims = t.count("[")
        t = t.lstrip("[")
        base = PRIM.get(t) or t[1:-1].split("/")[-1]
        return base + "[]" * dims
    if desc.startswith("("):
        args, ret = desc[1:].split(")", 1)
        return "(" + ", ".join(one(t) for t in TYPE.findall(args)) + ") " + one(ret)
    return one(desc)


def show(stubs, owner, member_rx):
    entry = stubs.entry_of(owner)
    if entry not in stubs.zip.namelist():
        print(f"{owner}: no such package in the database")
        return
    present, members, heads = set(), {}, {}
    for sid, head, lines in stubs.blocks(entry):
        if head.split(" ")[1] != owner:
            continue
        nodes = stubs.sets[sid]
        present |= nodes
        parts = head.split(" ")
        heads.setdefault(f"extends {parts[4]}" + (f" implements {parts[5]}" if parts[5] != "-" else ""),
                         set()).update(nodes)
        for line in lines:
            bits = line.strip().split(" ")
            if bits[0] not in ("field", "method"):
                continue
            kind, flags, name, desc = bits[0], bits[1], bits[2], bits[3]
            if member_rx and not re.search(member_rx, name):
                continue
            key = f"{kind} {name}{pretty(desc) if kind == 'method' else ' : ' + pretty(desc)}   [{flags}]"
            members.setdefault(key, set()).update(nodes)
    if not present:
        print(f"{owner}: not in any node")
        return
    print(f"{owner}")
    print(f"  exists on: {stubs.runs(present)}")
    missing = set(range(len(stubs.nodes))) - present
    if missing and len(missing) < len(stubs.nodes):
        print(f"  absent on: {stubs.runs(missing)}")
    if len(heads) > 1:
        for h, nodes in heads.items():
            print(f"  {h}: {stubs.runs(nodes)}")
    for key in sorted(members, key=lambda k: (k.split(" ")[1], k)):
        nodes = members[key]
        where = "every node that has the class" if nodes == present else stubs.runs(nodes)
        print(f"  {key}\n      {where}")
    if member_rx and not members:
        print(f"  no member matching /{member_rx}/")


def main():
    ap = argparse.ArgumentParser(description="The API of every node in stubs.zip.")
    ap.add_argument("what", help="a class name, or `find` / `nodes`")
    ap.add_argument("arg", nargs="?", help="member regex, or the class regex for `find`")
    ap.add_argument("--in", dest="scope", help="`find` only: a package prefix, e.g. net/minecraft/world")
    ap.add_argument("--zip", default=ZIP)
    a = ap.parse_args()
    stubs = Stubs(a.zip)
    if a.what == "nodes":
        print("\n".join(stubs.nodes))
    elif a.what == "find":
        for name in stubs.find_classes(a.arg or ".", a.scope):
            print(name)
    else:
        owners = stubs.resolve(a.what)
        if not owners:
            print(f"no class named {a.what}; try `find {a.what}`")
            sys.exit(1)
        if len(owners) > 1 and not a.what.count("/"):
            print(f"{len(owners)} classes named {a.what} -- showing each; name one fully to narrow:")
        for owner in owners:
            show(stubs, owner, a.arg)


if __name__ == "__main__":
    main()
