# Stubs — building every Minecraft version without installing every Minecraft

The 1.20.x tree builds one node per (loader, Minecraft version): 77 kinds of node, each compiled in
CrystalGraphics and again in CrystalGUI. **Stub mode compiles a node against `stubs.zip` instead of its
real game**: one committed database holding the signatures of every Minecraft, loader and library
class those nodes compile against — names, types and constants, no code.

| | Real mode | Stub mode |
|---|---|---|
| What a node compiles against | Minecraft set up by ModDevGradle, Loom or Unimined | its slice of `stubs.zip` |
| First jar build from a clean clone | ~38 GB on disk (17 GB project, 21 GB `~/.gradle`), ~3 hours | see the measurement below |
| Output | the thin jars | **byte-identical** thin jars |

`stubs.zip` is 16 MB, and it is the only thing committed. The first stub build on a machine unpacks it
**once** into `~/.gradle/caches/cg-stubs/<zip digest>/` — 101 MB of class files, one copy of each
distinct class, shared by every node, every clone and both repos. No node keeps a stub of its own.

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
once and tagged with the nodes it applies to — 16 MB zipped.

**Why the full API and not just what our code uses:** branch code is shared by every version of a
branch. A stub of only what we use would break on all ~22 Forge versions the first time a Forge host
called something new, and fixing it would need all 22 real toolchains. With the whole API, new code
just compiles; code that calls something a version lacks fails exactly as the real build would.

---

## Using it

```bash
./gradlew singleJar languageJar -PcgStubs        # every node in the database compiles from its stub
./gradlew :runtime:mc:modern:forge:1.20.4:runClient -PcgStubs   # this node real; the rest stubbed
./gradlew :runtime:mc:modern:forge:1.20.4:checkStubEquivalence  # real vs stub, byte for byte
```

> **`-PcgStubs` is opt-in today.** Making it the default is B2 in
> `plan/crystalgui/platform-single-jar/build-footprint.md`, after every node passes
> `checkStubEquivalence`.

**A node is real, whatever `-PcgStubs` says, when:**

| Condition | Why |
|---|---|
| One of its run or maintenance tasks is requested by path: `runClient`, `runServer`, `prepareClientRun`, `prepareServerRun`, `serverSmoke`, `connectionProbe`, `extractMcSources`, `genSourcesWithVineflower`, `checkStubEquivalence` | Running the game needs the game. It holds in either build of the composite, so running CrystalGUI's node makes CrystalGraphics' node of the same loader and version real too |
| `listStubInputs` is requested at all | It lists what the real toolchains supply |
| It is Stonecutter's active version (`stonecutter.gradle.kts`) | The IDE gets the whole game, with sources, where code is written |
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
./gradlew listStubInputs                                  # CrystalGUI's nodes
./gradlew -p CrystalGraphics listStubInputs               # CrystalGraphics' nodes
./gradlew -p CrystalGraphics/singlejar-logic generateStubDatabase   # writes stubs.zip, ~1 minute
./gradlew :runtime:mc:modern:<branch>:<version>:checkStubEquivalence   # the new node, both repos
```

`listStubInputs` writes each node's `build/stubs/inputs.txt`: the jars its toolchain supplies, its full
rename table and, on Fabric, the manifest attributes Loom writes. `generateStubDatabase` reads every
such file in both repos. Commit `stubs.zip` with the node.

`-PcgStubText` also writes the database as plain text under `singlejar-logic/stubs/` (gitignored), to
read or to diff two versions of it — the zip is binary, so git shows no diff of its own.

### Adding a new Minecraft version, start to finish

1. Add the node as `README.md` § *Many Minecraft versions* says — CrystalGraphics first, then CrystalGUI.
2. Build it real until it compiles (`-PcgRealNodes=<branch>:<version>`, or no `-PcgStubs`).
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
| `SrgReobfJar` with `stubDatabase` set | The Forge rename in stub mode; the node's names are cut from the zip for that run and deleted |
| `TinyRemapJar` | The Fabric rename in stub mode — tiny-remapper, without Loom; names the same way |
| `CompareOutputs` | The byte-for-byte check behind `checkStubEquivalence` |

**Inside `stubs.zip`:**

```
nodes.txt                 forge:1.20.1 ... one node per line; sets are numbered against this order
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
| Fabric | Loom's `remapJar` | `TinyRemapJar`: tiny-remapper 0.13.0, plus Loom's `Fabric-*` manifest |
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

---

## Checking it

`checkStubEquivalence` on a real node compiles every source set a second time against the stub, and
runs the stub build's rename over the real thin-dev jar, then compares both with the real output entry
by entry — manifests by attribute. Any difference fails, naming the entries.

Last run, 2026-09-25: 66 comparisons across every toolchain family (legacyForge 1.19.2, Unimined and
Loom 1.16.5, backported names 1.13.2, NeoForm+Forge 1.20.4, NeoForge, Fabric, 1.21.11), both repos —
all identical. `-PcgStubs singleJar languageJar` passes `checkSingle` and `checkLanguage`.

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
- **An unqualified `listStubInputs` makes every node real**, which is what it is for — do not add it to
  a build you want stubbed.
- **The names are Mojang's**, shipped in full. That is a decision taken knowingly, not an oversight.
