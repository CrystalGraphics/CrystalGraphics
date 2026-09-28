# Stubs — building every Minecraft version without installing every Minecraft

Two Stonecutter trees build one node per (loader, Minecraft version) — `runtime/mc/modern`, 77 kinds of
node for Forge 1.13+, NeoForge and Fabric, and `runtime/mc/legacy`, 3 for Forge 1.8–1.12.2 — each
compiled in CrystalGraphics and again in CrystalGUI. **Stub mode compiles a node against `stubs.zip` instead of its
real game**: one committed database holding the signatures of every Minecraft, loader and library
class those nodes compile against — names, types and constants, no code.

| | Real mode | Stub mode |
|---|---|---|
| What a node compiles against | Minecraft set up by ModDevGradle, Loom or Unimined | its slice of `stubs.zip` |
| First jar build from a clean clone | ~38 GB on disk (17 GB project, 21 GB `~/.gradle`), ~3 hours | **937 MB** project + 101 MB shared store, **5 minutes** |
| Output | the thin jars | **byte-identical** thin jars |

`stubs.zip` is 19 MB, and it is the only thing committed. The first stub build on a machine unpacks it
**once** into `~/.gradle/caches/cg-stubs/<zip digest>/` — 101 MB of class files, one copy of each
distinct class, shared by every node, every clone and both repos. No node keeps a stub of its own.

**Measured 2026-09-26**, a fresh clone running `./gradlew singleJar languageJar` — every node stubbed,
none of them holding a stub:

| Where | Size | What |
|---|---|---|
| `runtime/mc/1710`, both repos | 317 MB | 1.7.10's RetroFuturaGradle workspace, not stubbed yet (`legacy.md` L7) |
| both `.git` | 169 MB | the history, `stubs.zip` included |
| root `build/` | 153 MB | each shipped jar merged and finished — no stage copies (`build-footprint.md` B3) |
| `.gradle/9.5.1` | 113 MB | Gradle's own per-build state |
| all 154 nodes | 93 MB | compiled classes only — no stub, no Minecraft |
| `~/.gradle/caches/cg-stubs` | 101 MB | outside the project, once per machine |

The first clean clone, 2026-09-25, was 1.6 GB in 8 minutes: the active 1.20.1 nodes were still real on
the command line (~430 MB, now only during an IDE sync) and the pipeline kept six copies of each jar.

---

## Why stubs work

javac never runs the code it compiles against, and neither do the renamers (AutoRenamingTool,
tiny-remapper). They read **signatures**: which classes exist, what they extend, which members they
have and with what types. A class file with its method bodies replaced by `throw null` compiles
identically. That is all a stub is.

It is not a new idea. Android's `android.jar` is every platform API with no code, so apps build without
a phone; javac's own `lib/ct.sym` holds every JDK release's API, deduplicated, so `--release 8` works
without a Java 8 (11 MB for 18 releases). `stubs.zip` is `ct.sym` for Minecraft.

**Why a database and not a file per node:** consecutive versions share almost every class. The full
API of all 77 kinds of node is 3.25 GB of text as separate copies and 352 MB once each class is stored
once and tagged with the nodes it applies to — 16 MB zipped. The legacy tree's three nodes added 2.7 MB.

**Why the full API and not just what our code uses:** branch code is shared by every version of a
branch. A stub of only what we use would break on all ~22 Forge versions the first time a Forge host
called something new, and fixing it would need all 22 real toolchains. With the whole API, new code
just compiles; code that calls something a version lacks fails exactly as the real build would.

---

## Using it

```bash
./gradlew singleJar languageJar                  # every node in the database compiles from its stub
./gradlew singleJar languageJar -PcgStubs=false  # every node real, as before stubs
./gradlew :runtime:mc:modern:forge:1.20.4:runClient   # this node real; the rest stubbed
./gradlew :runtime:mc:modern:forge:1.20.4:checkStubEquivalence  # real vs stub, byte for byte
python mcapi.py PlayerList isOp                  # read the database: each spelling and the nodes it holds on
```

**Stub mode is the default.** A node is real anyway when:

| Condition | Why |
|---|---|
| One of its run or maintenance tasks is requested by path: `runClient`, `runServer`, `prepareClientRun`, `prepareServerRun`, `serverSmoke`, `connectionProbe`, `extractMcSources`, `genSourcesWithVineflower`, `checkStubEquivalence` | Running the game needs the game. It holds in either build of the composite, so running CrystalGUI's node makes CrystalGraphics' node of the same loader and version real too |
| `listStubInputs` is requested without a path, or on this node by path | It lists what the real toolchains supply |
| It is Stonecutter's active version (`stonecutter.gradle.kts`) **and an IDE is syncing** (`idea.sync.active`) | The IDE gets the whole game, with sources, where code is written; a command-line build stays stubbed |
| It is listed in `-PcgRealNodes=forge:1.20.4,1.21.1` | A bare version names every branch of it |
| `stubs.zip` has no entry for it | A new node builds real until the database is regenerated |

A stubbed node applies no Minecraft toolchain at all: no `createMinecraftArtifacts`, no Loom, no
Unimined, no Minecraft download.

---

## When to regenerate — and when not to

| You did | Regenerate? |
|---|---|
| Changed code in any branch, any node | **No.** The database holds the whole API |
| Called something a version does not have | **No.** That is a real compile error: add a Stonecutter `//? if` directive, as in real mode |
| Added a node (a new Minecraft version, or a loader on an existing one) | **Yes** |
| Changed a node's pins (Forge, NeoForge, Fabric API, Loom, mixin versions) | **Yes** |
| Upgraded ModDevGradle, Loom, Unimined or AutoRenamingTool | **Yes**, and run `checkStubEquivalence` |

A node missing from the database is never broken — it just builds real, downloading its toolchain.
Regenerating is what makes it cheap again for everyone else.

### Regenerating

Everything runs real for this, so it needs the toolchains once, on the maintainer's machine:

```bash
./gradlew :runtime:mc:<tree>:<branch>:<version>:listStubInputs              # CrystalGUI's node
./gradlew -p CrystalGraphics :runtime:mc:<tree>:<branch>:<version>:listStubInputs   # CrystalGraphics'
./gradlew -p CrystalGraphics/singlejar-logic generateStubDatabase   # writes stubs.zip, ~1 minute
./gradlew :runtime:mc:<tree>:<branch>:<version>:checkStubEquivalence   # the new node, both repos
```

`listStubInputs` runs the node's thin rename, then writes `build/stubs/inputs.txt` — the jars its
toolchain supplies, the rename table (kept beside it as `build/stubs/names.<format>`) and, on Fabric, the
manifest attributes Loom writes. `generateStubDatabase` reads every such file in both repos, so list only
the nodes that changed: the others' listings stay on disk. Unqualified, `./gradlew listStubInputs` lists
every node, all of them real. Commit `stubs.zip` with the node.

`-PcgStubText` also writes the database as plain text under `singlejar-logic/stubs/` (gitignored), to
read or to diff two versions of it — the zip is binary, so git shows no diff of its own.

### Adding a new Minecraft version, start to finish

1. Add the node as `README.md` § *Many Minecraft versions* says — CrystalGraphics first, then CrystalGUI.
2. Build it real until it compiles (`-PcgRealNodes=<branch>:<version>`, or `-PcgStubs=false`).
3. Regenerate (above), then `checkStubEquivalence` on the new node in both repos.
4. Commit the node and `stubs.zip` together.

---

## How it works

| Piece | Job |
|---|---|
| `StubDatabase` | Builds `stubs.zip` from every node's `inputs.txt`; cuts a node's rename table out of it |
| `StubSignatures` | The text form of one class, and the class file (bodies `throw null`) made from it |
| `StubMode.kt` | Decides stub or real per node; `configureStubs`, `registerThinRename`, `listStubInputs` |
| `StubStore` | Unpacks `stubs.zip` once per machine into one jar per node set; hands a node the set jars that include it |
| `SrgReobfJar` with `stubDatabase` set | The Forge rename in stub mode; the node's names are cut from the zip for that run and deleted. With `mcpSrg`/`mcpMappings` set, the legacy real rename |
| `TinyRemapJar` | The Fabric rename in stub mode — tiny-remapper, without Loom; names the same way |
| `CompareOutputs` | The byte-for-byte check behind `checkStubEquivalence` |

**Inside `stubs.zip`:**

```
nodes.txt                 forge:1.20.1 ... legacy/forge:1.12.2 -- one node per line; sets are numbered against this order
sets.txt                  1 common:1.19.2-1.21.11,forge:1.19.2-1.21.11,neoforge:1.20.2-1.21.11
manifests.txt             fabric:1.20.4 Fabric-Loom-Version 1.16.2
api/net.minecraft.world.sig   classes, each block headed `in <set>`
names/srg.tsrg            Forge's official -> SRG, per class, the same way
names/intermediary.tiny   Fabric's named -> intermediary, the same way
```

```
in 1
class net/minecraft/client/gui/screens/Screen 61 public,super,abstract net/minecraft/... -
 field protected title Lnet/minecraft/network/chat/Component; -
 method protected <init> (Lnet/minecraft/network/chat/Component;)V - -
```

**What a node's API holds:** every public and protected class member, and every class except
anonymous and local ones, from every jar on its compile classpath that no project of ours builds or
brings. Libraries our own projects depend on (ECJ and Rhino, through `:language`) are left out: they resolve
in stub mode anyway.

**Renaming**, where a loader runs other names than a node compiles against — run with the tool and
arguments the real build uses, so the output is the same:

| Node | Real build | Stub build |
|---|---|---|
| Forge 1.17–1.20.1 (legacyForge) | ModDevGradle: AutoRenamingTool 2.0.4, `--strip-sigs` | the same tool and arguments, `SrgReobfJar` |
| Forge 1.13–1.16, 1.20.2–1.20.4 | `SrgReobfJar`: AutoRenamingTool 2.0.17 | the same, over the database's names |
| Fabric | Loom's `remapJar` | `TinyRemapJar`: tiny-remapper 0.13.0 with `--mixin` (Loom remaps a mixin's annotation strings too), plus Loom's `Fabric-*` manifest |
| Legacy Forge 1.8–1.12.2 | `SrgReobfJar` from MCP's `joined.srg` + CSVs (`registerMcpReobf`): AutoRenamingTool 2.0.17 | the same, over the database's names |
| NeoForge, Forge 1.20.6+ | none — Mojang's names | none |

**In a branch script**, the rule is: ask `stubMode` before touching a toolchain, and rename through
`registerThinRename`.

```kotlin
if (!stubMode) {
    apply(plugin = "fabric-loom")               // `apply false` in plugins {}: a stub node never applies it
    the<LoomGradleExtensionAPI>().runs { ... }  // no generated accessors -- the plugin is not in plugins {}
}
val thinJar = registerThinRename("thinShadowJar", "thin") {
    registerSrgReobf("thinShadowJar", "thin", sourceSets.main.get().compileClasspath)   // real mode only
}
```

**More than one tree.** A node is found wherever `runtime/mc/<tree>/<branch>/versions/<version>/`
holds a `build/stubs/inputs.txt`, and keyed `<tree>/<branch>:<version>` — except on the modern tree,
whose keys (`forge:1.20.1`) predate the others and stay unprefixed, so adding a tree changes no modern
entry: adding `legacy/forge:1.8.9`, `:1.10.2` and `:1.12.2` left all 77 modern nodes' signatures
identical. A tree's node convention calls `configureStubs` last and renames through `registerThinRename`, as
the modern ones do; legacy Forge's rename is MCP names -> SRG members (`registerMcpReobf`).

---

## Checking it

`checkStubEquivalence` on a real node compiles every source set a second time against the stub, and
runs the stub build's rename over the real thin-dev jar, then compares both with the real output entry
by entry — manifests by attribute. Any difference fails, naming the entries.

Last run, 2026-09-26: every node of both repos, 330 comparisons, all identical; `singleJar` and
`languageJar` built all-real (`-PcgStubs=false`) and stubbed agree on all 6,440 entries. Their manifests
differ only where the all-real build wrote Loom's `unknown` (see Traps) — the stub build's is the right one.
The legacy tree's three nodes, both repos, `main` and `lang`: identical, the same day.
---

## Traps

- **`property("x")` inside a task's configuration lambda asks the task, not the project**, and fails
  with "unknown property". Read it into a local first.
- **Unimined puts Minecraft on the source set's classpath directly**, not through a configuration. The
  jars a stub replaces are therefore read off the compile tasks (`stubTargets`), not the configurations.
- **A toolchain in `plugins {}` is applied to every node**, stub or not. Declare it `apply false` and
  apply it under `if (!stubMode)`; its DSL accessors go with it, so use `the<…>()` and string
  configuration names (`"modImplementation"(…)`).
- **A task that depends on a run task names it by string** (`dependsOn("runServer")`): a stub node has
  no `runServer`, and requesting `serverSmoke` makes the node real anyway.
- **An interface's `default` method implementing a super-interface's abstract one** is exactly what a
  pared-down stub drops, and javac then refuses a lambda for it ("not a functional interface"). The full
  API keeps it; anything that trims the API must keep it too.
- **Loom writes `Fabric-Loader-Version: unknown`** (and the mixin pair) when it has not resolved them
  yet, and the shipped jar copies those attributes from the first Fabric node. `listStubInputs` refuses
  `unknown`; rebuild that node's `remapThinJar` with `--rerun-tasks` and list it again.
- **An unqualified `listStubInputs` makes every node real**, which is what it is for — do not add it to
  a build you want stubbed.
- **The names are Mojang's**, shipped in full. That is a decision taken knowingly, not an oversight.
