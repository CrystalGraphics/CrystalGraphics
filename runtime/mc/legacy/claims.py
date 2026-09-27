"""Checks every Minecraft and Forge reference a shipped legacy node makes against each version it claims.

    python runtime/mc/legacy/claims.py build/libs/crystalgraphics-1.0.0.jar [more merged jars...]

A node's claim is its `variant.minecraft` range; every Minecraft version with a Forge build inside it is
checked. Minecraft members by (SRG id, descriptor) in that version's MCP `joined.srg`, so a descriptor that
changed under a stable id is caught; Forge classes and members in that version's Forge universal jar.
Constructors appear in neither table: a boot is still needed. Downloads are cached under build/legacy-claims.
"""
import io, json, os, re, struct, sys, urllib.request, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "..", "..", "..", "build", "legacy-claims")
FORGE = "https://maven.minecraftforge.net/"


def get(url, name):
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        os.makedirs(CACHE, exist_ok=True)
        data = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "claims"}), timeout=180).read()
        open(path, "wb").write(data)
    return open(path, "rb").read()


def parse(b):
    """A class file's (name, super, member refs {(kind, owner, name, desc)}, class refs, declared {(name, desc)})."""
    n = struct.unpack(">H", b[8:10])[0]
    cp, i, k = [None] * n, 10, 1
    while k < n:
        t = b[i]
        if t == 1:
            ln = struct.unpack(">H", b[i + 1:i + 3])[0]; cp[k] = b[i + 3:i + 3 + ln].decode("utf-8", "replace"); i += 3 + ln
        elif t == 7: cp[k] = ("c", struct.unpack(">H", b[i + 1:i + 3])[0]); i += 3
        elif t in (9, 10, 11): cp[k] = ("r", t) + struct.unpack(">HH", b[i + 1:i + 5]); i += 5
        elif t == 12: cp[k] = ("n",) + struct.unpack(">HH", b[i + 1:i + 5]); i += 5
        elif t in (3, 4): i += 5
        elif t in (5, 6): i += 9; k += 1
        elif t in (8, 16, 19, 20): i += 3
        elif t == 15: i += 4
        else: i += 5
        k += 1
    cls = lambda x: cp[cp[x][1]]
    this, sup = cls(struct.unpack(">H", b[i + 2:i + 4])[0]), struct.unpack(">H", b[i + 4:i + 6])[0]
    i += 8 + 2 * struct.unpack(">H", b[i + 6:i + 8])[0]
    declared = set()
    for _ in range(2):  # fields, then methods
        count = struct.unpack(">H", b[i:i + 2])[0]; i += 2
        for _ in range(count):
            _, ni, di, ac = struct.unpack(">HHHH", b[i:i + 8]); i += 8
            declared.add((cp[ni], cp[di]))
            for _ in range(ac):
                i += 6 + struct.unpack(">I", b[i + 2:i + 6])[0]
    refs, crefs = set(), set()
    for e in cp:
        if isinstance(e, tuple) and e[0] == "c":
            crefs.add(cp[e[1]])
        if isinstance(e, tuple) and e[0] == "r":
            nt = cp[e[3]]
            refs.add(("F" if e[1] == 9 else "M", cls(e[2]), cp[nt[1]], cp[nt[2]]))
    return this, cls(sup) if sup else None, refs, crefs, declared


def key(v):
    return tuple(int(x) for x in v.split("."))


def claims():
    """{package segment: [claimed Minecraft versions with a Forge build]}"""
    promos = json.loads(get("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json",
                            "promotions_slim.json"))["promos"]
    published = sorted({p.rsplit("-", 1)[0] for p in promos}, key=key)
    out = {}
    root = os.path.join(HERE, "forge", "versions")
    for node in sorted(os.listdir(root), key=key):
        props = open(os.path.join(root, node, "gradle.properties")).read()
        low, high = re.search(r"^variant\.minecraft\s*=\s*\[([\d.]+),([\d.]+)\)", props, re.M).groups()
        out["v" + node.replace(".", "")] = [v for v in published if key(low) <= key(v) < key(high)]
    return out, promos


def srg_for(v):
    """MCP's joined.srg for v; MCP published none for some patch versions, which share their minor's."""
    for mcp in (v, v.rsplit(".", 1)[0]):
        try:
            return zipfile.ZipFile(io.BytesIO(get(FORGE + f"de/oceanlabs/mcp/mcp/{mcp}/mcp-{mcp}-srg.zip",
                                                  f"mcp-{mcp}-srg.zip"))).read("joined.srg").decode()
        except Exception:
            pass
    raise SystemExit("no joined.srg for " + v)


def forge_for(v, build):
    """Forge's universal jar; its coordinate carries a branch suffix on some versions."""
    for coord in (f"{v}-{build}", f"{v}-{build}-{v}", f"{v}-{build}-{v}.0"):
        try:
            return zipfile.ZipFile(io.BytesIO(get(FORGE + f"net/minecraftforge/forge/{coord}/forge-{coord}-universal.jar",
                                                  f"forge-{coord}-universal.jar")))
        except Exception:
            pass
    raise SystemExit("no Forge universal jar for " + v)


def main(jars):
    nodes, promos = claims()
    refs = {p: set() for p in nodes}
    crefs = {p: set() for p in nodes}
    for jar in jars:
        z = zipfile.ZipFile(jar)
        for name in z.namelist():
            m = re.match(r"com/crystal\w+/mc/(v\d+)/.*\.class$", name)
            if m and m.group(1) in nodes:
                _, _, r, c, _ = parse(z.read(name))
                refs[m.group(1)] |= r
                crefs[m.group(1)] |= c
    problems = 0
    for node, versions in nodes.items():
        print(f"{node}: {len(refs[node])} member refs, claims {', '.join(versions)}")
        for v in versions:
            srg = srg_for(v)
            classes = set(re.findall(r"^CL: \S+ (\S+)", srg, re.M))
            fields = set(re.findall(r"^FD: \S+ \S+/(\w+)", srg, re.M))
            methods = set(re.findall(r"^MD: \S+ \S+ \S+/(\w+) (\S+)", srg, re.M))
            build = promos.get(v + "-recommended") or promos.get(v + "-latest")
            fz = forge_for(v, build)
            forge = {}
            for n in fz.namelist():
                if n.endswith(".class") and n.startswith("net/minecraftforge/"):
                    this, sup, _, _, decl = parse(fz.read(n))
                    forge[this] = (sup, decl)

            def forge_has(owner, name, desc):
                while owner in forge:
                    sup, decl = forge[owner]
                    if any(d[0] == name and (desc is None or d[1] == desc) for d in decl):
                        return True
                    owner = sup
                # Walked out of Forge into Minecraft or the JDK: not Forge's to answer.
                return owner is not None and not owner.startswith("net/minecraftforge/")

            found = []
            for c in sorted(crefs[node]):
                if c.startswith("net/minecraft/") and not c.startswith("net/minecraft/launchwrapper/") \
                        and "$" not in c and c not in classes:
                    found.append("class " + c)
                if c.startswith("net/minecraftforge/") and c not in forge:
                    found.append("Forge class " + c)
            for kind, owner, name, desc in sorted(refs[node]):
                if owner.startswith("net/minecraft/"):
                    if name.startswith("func_") and (name, desc) not in methods:
                        found.append(f"{owner}.{name}{desc}")
                    elif name.startswith("field_") and name not in fields:
                        found.append(f"{owner}.{name}")
                elif owner.startswith("net/minecraftforge/") and not forge_has(owner, name, desc if kind == "M" else None):
                    found.append(f"Forge {owner}.{name}{desc if kind == 'M' else ''}")
            problems += len(found)
            print(f"  {v:7} Forge {build}: " + ("OK" if not found else f"{len(found)} missing"))
            for f in found:
                print("      " + f)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main(sys.argv[1:] or [os.path.join(HERE, "..", "..", "..", "build", "libs", "crystalgraphics-1.0.0.jar")])
