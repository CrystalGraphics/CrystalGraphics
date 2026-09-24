"""Mojang-shaped names for Minecraft 1.13.2, which Mojang never published.

Members are chained through SRG ids, which MCPConfig keeps stable between 1.13.2 and 1.14.4:
  1.13.2 obf --(MCPConfig 1.13.2)--> srg id <--(MCPConfig 1.14.4)-- 1.14.4 obf <--(Mojang 1.14.4)-- name
Classes are matched by HINTS first, then an identical MCP class name, then a vote of the member ids that
have a single 1.14.4 owner. Members of classes 1.14.4 left unobfuscated are bridged by MCP name, and
MEMBER_ALIASES names ids 1.14 retired. Unmatched classes and members keep their obfuscated names.
Writes TSRG2 with namespaces `official` and `mojmap`.

    python backport_mojmap.py mojmap-1.13.2.tsrg

Unimined caches what it remapped under a name that does not change with this file: after regenerating,
delete ~/.gradle/caches/unimined/net/minecraft/minecraft/1.13.2/{mojmap,mappings} and the Forge
`mojmap-searge-*` directory, or a dev build keeps the old names.
"""
import collections, io, json, re, sys, urllib.request, zipfile

UA = {"User-Agent": "crystalgui-build/1.0"}
OUT = sys.argv[1] if len(sys.argv) > 1 else "mojmap-1.13.2.tsrg"


def get(u):
    return urllib.request.urlopen(urllib.request.Request(u, headers=UA)).read()


def mcp_tsrg(version):
    z = zipfile.ZipFile(io.BytesIO(get(f"https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/{version}/mcp_config-{version}.zip")))
    return z.read("config/joined.tsrg").decode()


def parse_tsrg(txt):
    classes, fields, methods = {}, {}, {}  # obf class -> srg class; (cls, obf) -> srg; (cls, obf, desc) -> srg
    cur = None
    for line in txt.splitlines():
        if not line.strip():
            continue
        if not line.startswith("\t"):
            obf, srg = line.split()
            classes[obf] = srg
            cur = obf
            continue
        parts = line.strip().split()
        if len(parts) == 2:
            fields[(cur, parts[0])] = parts[1]
        else:
            methods[(cur, parts[0], parts[1])] = parts[2]
    return classes, fields, methods


PRIM = {"byte": "B", "char": "C", "double": "D", "float": "F", "int": "I", "long": "J", "short": "S", "boolean": "Z", "void": "V"}


def jdesc(t, cmap):
    dims = t.count("[]")
    t = t.replace("[]", "")
    d = PRIM.get(t) or "L" + cmap.get(t, t).replace(".", "/") + ";"
    return "[" * dims + d


def parse_mojang(txt):
    """named class -> obf class; (obf cls, obf name) -> named field; (obf cls, obf name, obf desc) -> named method"""
    cls = {}
    for line in txt.splitlines():
        if line and not line.startswith((" ", "#")):
            named, obf = line.rstrip(":").split(" -> ")
            cls[named] = obf.replace(".", "/")
    fields, methods = {}, {}
    cur = None
    for line in txt.splitlines():
        if not line or line.startswith("#"):
            continue
        if not line.startswith(" "):
            cur = cls[line.rstrip(":").split(" -> ")[0]]
            continue
        body, obf = line.strip().split(" -> ")
        body = re.sub(r"^\d+:\d+:", "", body)
        typ, rest = body.split(" ", 1)
        if "(" in rest:
            name, params = rest.split("(", 1)
            params = params.rstrip(")")
            desc = "(" + "".join(jdesc(p, cls) for p in params.split(",") if p) + ")" + jdesc(typ, cls)
            methods[(cur, obf, desc)] = name
        else:
            fields[(cur, obf)] = rest
    return cls, fields, methods


manifest = json.loads(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
urls = {v["id"]: v["url"] for v in manifest["versions"]}
moj = get(json.loads(get(urls["1.14.4"]))["downloads"]["client_mappings"]["url"]).decode()
m_cls, m_fields, m_methods = parse_mojang(moj)
obf_to_named_1144 = {o: n for n, o in m_cls.items()}

c14, f14, me14 = parse_tsrg(mcp_tsrg("1.14.4-20190829.143755"))
c13, f13, me13 = parse_tsrg(mcp_tsrg("1.13.2-20190213.203750"))

# srg id -> (mojang name, owning 1.14.4 class in Mojang names)
srg_field, srg_method = {}, {}
for (c, o), s in f14.items():
    n = m_fields.get((c, o))
    if n:
        srg_field[s] = (n, obf_to_named_1144.get(c))
for (c, o, d), s in me14.items():
    n = m_methods.get((c, o, d))
    if n and s.startswith("func_"):
        srg_method[s] = (n, obf_to_named_1144.get(c))

# classes: an identical MCP class name first, else a vote by members whose SRG id has ONE owner --
# interfaces and their implementations share method ids, so a shared id says nothing about the class
srg_class_1144 = {s: obf_to_named_1144.get(o) for o, s in c14.items()}
owners = collections.defaultdict(set)
for (c, o), s_ in f14.items():
    owners[s_].add(obf_to_named_1144.get(c))
for (c, o, d), s_ in me14.items():
    owners[s_].add(obf_to_named_1144.get(c))
class_named = {}
for obf, srg in c13.items():
    if srg in srg_class_1144 and srg_class_1144[srg]:
        class_named[obf] = srg_class_1144[srg]
        continue
    votes = collections.Counter()
    for (c, o), s_ in f13.items():
        if c == obf and len(owners.get(s_, ())) == 1:
            votes[next(iter(owners[s_]))] += 1
    for (c, o, d), s_ in me13.items():
        if c == obf and len(owners.get(s_, ())) == 1:
            votes[next(iter(owners[s_]))] += 1
    votes.pop(None, None)
    if votes:
        best, n = votes.most_common(1)[0]
        if n >= 2 or len(votes) == 1:
            class_named[obf] = best
# renames the member vote cannot see (few members, or members Mojang left unobfuscated)
HINTS = {
    "net/minecraft/util/text/TextComponentString": "net/minecraft/network/chat/TextComponent",
    "net/minecraft/resources/IResourceManager": "net/minecraft/server/packs/resources/ResourceManager",
    "net/minecraft/resources/IResource": "net/minecraft/server/packs/resources/Resource",
    "net/minecraft/resources/IReloadableResourceManager": "net/minecraft/server/packs/resources/ReloadableResourceManager",
    "net/minecraft/server/integrated/IntegratedServer": "net/minecraft/client/server/IntegratedServer",
    "net/minecraft/client/renderer/OpenGlHelper": "com/mojang/blaze3d/platform/GLX",
    "net/minecraft/util/registry/IRegistry": "net/minecraft/core/Registry",
    "net/minecraft/client/renderer/GlStateManager": "com/mojang/blaze3d/platform/GlStateManager",
    "net/minecraft/resources/IResourceManagerReloadListener": "net/minecraft/server/packs/resources/ResourceManagerReloadListener",
    "net/minecraft/world/storage/ISaveFormat": "net/minecraft/world/level/storage/LevelStorageSource",
}
# members whose SRG id 1.14 retired, named for their 1.14 successor
MEMBER_ALIASES = {
    "func_199006_a": "registerReloadListener",
    # GuiScreen: 1.14 moved these onto new ids
    "func_73866_w_": "init",
    "func_73863_a": "render",
    "func_73876_c": "tick",
    "func_146281_b": "removed",
    "func_73868_f": "isPauseScreen",
    "func_195120_Y_": "shouldCloseOnEsc",
    "func_195122_V_": "onClose",
}
# members left unnamed: Forge 25 adds a member of the same name (and descriptor, for a method) to the class
MEMBER_SKIP = {"func_174898_m", "func_177523_a", "field_70180_af"}
srg13_to_obf = {v: k for k, v in c13.items()}
hinted = set()
for mcp, named in HINTS.items():
    if mcp in srg13_to_obf:
        class_named[srg13_to_obf[mcp]] = named.replace("/", ".")
        hinted.add(srg13_to_obf[mcp])
    else:
        print("hint names no 1.13.2 class:", mcp)
# one 1.13.2 class per Mojang name: on a clash keep the hinted one, else the one whose MCP name agrees
seen = {}
for obf, named in list(class_named.items()):
    if named in seen:
        other = seen[named]
        keep = obf if obf in hinted or (other not in hinted and srg_class_1144.get(c13[obf]) == named) else other
        drop = other if keep == obf else obf
        class_named.pop(drop)
        seen[named] = keep
    else:
        seen[named] = obf
# inner classes follow their outer class's rename when unmatched
for obf in c13:
    if obf not in class_named and "$" in obf:
        outer, inner = obf.split("$", 1)
        if outer in class_named:
            class_named[obf] = class_named[outer] + "$" + inner

zcsv = zipfile.ZipFile(io.BytesIO(get("https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/47-1.13.2/mcp_stable-47-1.13.2.zip")))
mcp_name = {}
for f in ("methods.csv", "fields.csv"):
    for row in zcsv.read(f).decode().splitlines()[1:]:
        cols = row.split(",")
        mcp_name[cols[0]] = cols[1]
unobf_1144 = {n for n, o in m_cls.items() if n.replace(".", "/") == o}
bridge_methods, bridge_fields = {}, {}
for (c, o, d), srgid in me13.items():
    named_cls = class_named.get(c)
    if not named_cls or named_cls not in unobf_1144 or srgid in srg_method:
        continue
    n = mcp_name.get(srgid)
    cls144 = named_cls.replace(".", "/")
    if n and (cls144, n, d) in m_methods:   # primitive-only descriptors compare directly
        bridge_methods[(c, o, d)] = n
for (c, o), srgid in f13.items():
    named_cls = class_named.get(c)
    if not named_cls or named_cls not in unobf_1144 or srgid in srg_field:
        continue
    n = mcp_name.get(srgid)
    if n and (named_cls.replace(".", "/"), n) in m_fields:
        bridge_fields[(c, o)] = n
print("bridged", len(bridge_methods), "methods and", len(bridge_fields), "fields by MCP name")

out = ["tsrg2 official mojmap"]
stats = collections.Counter()
for obf in sorted(c13):
    named = class_named.get(obf, obf)
    out.append(f"{obf} {named.replace('.', '/')}")
    stats["class_mapped" if obf in class_named else "class_kept"] += 1
    used = set()
    for (c, o), s in sorted(f13.items()):
        if c != obf:
            continue
        if s in MEMBER_SKIP:
            continue
        n = srg_field.get(s, (None,))[0] or bridge_fields.get((c, o))
        if n and ("f", n) not in used:
            used.add(("f", n))
            out.append(f"\t{o} {n}")
            stats["field"] += 1
    for (c, o, d), s in sorted(me13.items()):
        if c != obf:
            continue
        if s in MEMBER_SKIP:
            continue
        n = (srg_method.get(s, (None,))[0] if s.startswith("func_") else None) or bridge_methods.get((c, o, d)) or MEMBER_ALIASES.get(s)
        if n and ("m", n, d) not in used:
            used.add(("m", n, d))
            out.append(f"\t{o} {d} {n}")
            stats["method"] += 1
open(OUT, "w", newline="\n").write("\n".join(out) + "\n")
print(dict(stats), "->", OUT)
for probe in ["net.minecraft.client.Minecraft", "net.minecraft.client.gui.screens.Screen", "com.mojang.blaze3d.platform.GlStateManager",
              "com.mojang.blaze3d.platform.GLX", "net.minecraft.client.gui.Gui", "net.minecraft.client.renderer.GameRenderer",
              "com.mojang.blaze3d.platform.Window", "net.minecraft.server.MinecraftServer", "net.minecraft.client.KeyMapping"]:
    hit = [o for o, n in class_named.items() if n == probe]
    print(f"{probe:48} <- {hit} {c13.get(hit[0]) if hit else ''}")
