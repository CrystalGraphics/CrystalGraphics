#!/usr/bin/env python3
"""
mcrender -- what changed in Minecraft's RENDERING CONVENTIONS between two versions, read from the jars.

    python mcrender.py 26.1.2 26.2                   # every convention fact that differs
    python mcrender.py 26.1.2 26.2 --api             # plus classes and members added and removed
    python mcrender.py 26.1.2 26.2 --only Projection # facts from classes whose path matches a regex
    python mcrender.py 26.2 --facts --only GlDevice  # one version's facts, no diff

A convention is what a mod drawing inside Minecraft's frame has to match and cannot see in an API
signature: the depth test a pipeline defaults to, the clear value, the clip range enabled at device
init, the order a projection receives its planes in, the GL enum a format maps to. mcapi.py answers
"does this member exist"; this answers "does the frame still work the same way".

Facts, per method, from `javap -c` over com/mojang/blaze3d and net/minecraft/client/renderer:

  const   a static final constant                         RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE = 0.0d
  enum    an enum's constants, in order                   CompareOp = [ALWAYS_PASS, LESS_THAN, ...]
  gl      a GL/Vulkan call and its literal arguments      GlDevice.<init>: ARBClipControl.glClipControl(36001, 37727)
  joml    a JOML matrix call and the fields feeding it    Projection.getMatrix: Matrix4f.setPerspective(<- zFar, zNear, ...)
  uses    a Blaze3D enum constant a method selects        DepthStencilState.<clinit> -> CompareOp.GREATER_THAN_OR_EQUAL
  body    the whole body of a *Const class's method       GlConst.toGl(CompareOp): the mapping table

Unobfuscated jars only, which is every version from 26.1: before it the names are Notch's and a diff
by name means nothing -- compare those at run time (-Dcrystalgraphics.host.census) instead.
Needs `javap` (any JDK) on PATH. Jars are read from Prism's library cache, or pass --jar A --jar B.
"""

import argparse
import os
import re
import subprocess
import sys
import zipfile

PACKAGES = ("com/mojang/blaze3d/", "net/minecraft/client/renderer/")
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build", "mcrender")
DEFAULT_LIBRARIES = os.path.join(os.environ.get("APPDATA", ""), "PrismLauncher", "libraries")

LITERAL = re.compile(r"^(?:[ilfd]const_(m1|\d)|[bs]ipush\s+(-?\d+)|ldc(?:2?_w)?\s+#\d+\s+//\s+\w+\s+(.+))$")
INVOKE = re.compile(r"^invoke(static|virtual|interface|special)\s+#\d+\s+//\s+(?:Interface)?Method\s+(\S+?)\.(\S+?):\((.*?)\)")
GETFIELD = re.compile(r"^get(field|static)\s+#\d+\s+//\s+Field\s+(?:(\S+?)\.)?(\w+):(\S+)")
METHOD_HEAD = re.compile(r"^  .*?([\w$<>]+)\((.*?)\)(?:\s+throws\s+.*)?;$")
STATIC_INIT = "  static {};"
CONST_FIELD = re.compile(r"^  (?:public |protected |private )?static final \S+ (\w+) = (.+);$")
ENUM_FIELD = re.compile(r"^  public static final (\S+) (\w+);$")


def jar_path(version, libraries):
    path = os.path.join(libraries, "com", "mojang", "minecraft", version, f"minecraft-{version}-client.jar")
    if not os.path.isfile(path):
        sys.exit(f"no client jar for {version} at {path}; pass --jar")
    return path


def extract(jar):
    """The rendering packages of `jar`, unpacked once into build/mcrender/<jar name>/."""
    out = os.path.join(CACHE, os.path.splitext(os.path.basename(jar))[0])
    marker = os.path.join(out, ".done")
    if not os.path.exists(marker):
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.endswith(".class") and name.startswith(PACKAGES):
                    z.extract(name, out)
        open(marker, "w").close()
    return out


def classes(root):
    names = []
    for base, _, files in os.walk(root):
        for f in files:
            if f.endswith(".class"):
                rel = os.path.relpath(os.path.join(base, f), root).replace(os.sep, "/")
                names.append(rel[:-6])
    return sorted(names)


def javap(root, names):
    """`javap -p -c -constants` over `names`, in batches; the text per class."""
    text = {}
    for i in range(0, len(names), 200):
        batch = names[i:i + 200]
        out = subprocess.run(["javap", "-p", "-c", "-constants", "-cp", root] + [n.replace("/", ".") for n in batch],
                             capture_output=True, text=True, errors="replace").stdout
        current = None
        for line in out.splitlines(keepends=True):
            if line.startswith("Compiled from"):
                continue
            m = re.match(r"^(?:public |protected |private |abstract |final |static |sealed |non-sealed )*(?:class|interface|enum|record|@interface) ([\w.$]+)", line)
            if m and not line.startswith(" "):
                current = m.group(1).replace(".", "/")
                text[current] = []
            if current:
                text[current].append(line.rstrip("\n"))
    return text


def short(owner):
    return owner.rsplit("/", 1)[-1]


def literal(instruction):
    """The value an instruction pushes, when it is a constant; None otherwise."""
    m = LITERAL.match(instruction)
    if not m:
        return None
    value = m.group(1) or m.group(2) or m.group(3)
    return "-1" if value == "m1" else value


def arg_count(descriptor):
    return len(re.findall(r"\[*(?:L[^;]+;|[BCDFIJSZ])", descriptor))


def facts_of(cls, lines):
    """The convention facts of one class, as a set of strings."""
    facts, api = set(), set()
    name = short(cls)
    enum_consts = []
    method, body, code = None, [], False

    def close_method():
        if method is None:
            return
        instrs = [re.sub(r"^\s*\d+:\s*", "", l) for l in body]
        # Every field the method reads, in order: a projection's planes are often staged in locals, so
        # the reads just before the call miss them, while a swap of two planes still reorders this list.
        fields_read = []
        for idx, ins in enumerate(instrs):
            g = GETFIELD.match(ins)
            if g:
                owner, field = g.group(2), g.group(3)
                if field not in fields_read:
                    fields_read.append(field)
                if owner and owner.startswith("com/mojang/blaze3d/") and g.group(1) == "static" \
                        and g.group(4) == f"L{owner};" and field.isupper():
                    facts.add(f"uses   {name}.{method} -> {short(owner)}.{field}")
                continue
            m = INVOKE.match(ins)
            if not m:
                continue
            owner, meth, desc = m.group(2), m.group(3), m.group(4)
            if owner.startswith("org/lwjgl/") and re.match(r"^(gl|vk)[A-Z]", meth):
                args = [literal(back) or "?" for back in instrs[max(0, idx - arg_count(desc)):idx]]
                facts.add(f"gl     {name}.{method}: {short(owner)}.{meth}({', '.join(args)})")
            elif owner.startswith("org/joml/"):
                facts.add(f"joml   {name}.{method}: {short(owner)}.{meth}({desc}) reading {', '.join(fields_read)}")
        if re.search(r"Const(\$|$)", name) and method != "<clinit>":
            joined = "; ".join(re.sub(r"#\d+", "#", i) for i in instrs)
            facts.add(f"body   {name}.{method}: {joined[:4000]}")

    for line in lines:
        c = CONST_FIELD.match(line)
        if c:
            facts.add(f"const  {name}.{c.group(1)} = {c.group(2)}")
            api.add(f"{name}: {line.strip()}")
            continue
        e = ENUM_FIELD.match(line)
        if e and e.group(1).replace(".", "/") == cls:
            enum_consts.append(e.group(2))
        if line.startswith("  ") and not line.startswith("    ") and line.rstrip().endswith(";"):
            close_method()
            method, body, code = None, [], False
            if line == STATIC_INIT:
                method = "<clinit>"
            else:
                h = METHOD_HEAD.match(line)
                if h:
                    method = "<init>" if h.group(1).replace("$", ".").endswith(name.replace("$", ".")) else h.group(1)
                    method = f"{method}({h.group(2)})" if method not in ("<clinit>",) else method
            api.add(f"{name}: {line.strip()}")
            continue
        if line.strip() == "Code:":
            code = True
            continue
        if code and re.match(r"^\s+\d+:", line):
            body.append(line)
    close_method()
    if enum_consts:
        facts.add(f"enum   {name} = [{', '.join(enum_consts)}]")
    return facts, api


def fingerprint(version_or_jar, libraries, only):
    jar = version_or_jar if version_or_jar.endswith(".jar") else jar_path(version_or_jar, libraries)
    root = extract(jar)
    names = [n for n in classes(root) if not only or re.search(only, n)]
    facts, api, present = set(), set(), set(names)
    for cls, lines in javap(root, names).items():
        f, a = facts_of(cls, lines)
        facts |= f
        api |= a
    return facts, api, present


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("versions", nargs="+", help="one version (with --facts) or two to compare")
    ap.add_argument("--jar", action="append", help="explicit client jars instead of versions, in order")
    ap.add_argument("--libraries", default=DEFAULT_LIBRARIES, help="Prism's library cache")
    ap.add_argument("--only", help="regex over class paths")
    ap.add_argument("--api", action="store_true", help="also list classes and members added and removed")
    ap.add_argument("--facts", action="store_true", help="print one version's facts instead of a diff")
    a = ap.parse_args()
    sources = a.jar or a.versions

    if a.facts:
        facts, _, _ = fingerprint(sources[0], a.libraries, a.only)
        print("\n".join(sorted(facts)))
        return
    if len(sources) != 2:
        sys.exit("two versions to compare, or --facts with one")

    (fa, aa, ca), (fb, ab, cb) = (fingerprint(s, a.libraries, a.only) for s in sources)
    print(f"# {sources[0]} -> {sources[1]}: {len(fa ^ fb)} convention facts differ\n")
    for kind in ("const", "enum", "gl", "joml", "uses", "body"):
        gone = sorted(f for f in fa - fb if f.startswith(kind))
        new = sorted(f for f in fb - fa if f.startswith(kind))
        if not gone and not new:
            continue
        print(f"## {kind}")
        for f in gone:
            print(f"- {f}")
        for f in new:
            print(f"+ {f}")
        print()
    if a.api:
        print("## classes")
        for c in sorted(ca - cb):
            print(f"- {c}")
        for c in sorted(cb - ca):
            print(f"+ {c}")
        print("\n## members of classes in both")
        both = {short(c) for c in ca & cb}
        for m in sorted(x for x in aa - ab if x.split(":")[0] in both):
            print(f"- {m}")
        for m in sorted(x for x in ab - aa if x.split(":")[0] in both):
            print(f"+ {m}")


if __name__ == "__main__":
    main()
