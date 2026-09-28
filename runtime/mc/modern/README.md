# runtime/mc/modern — one source tree, a node per Minecraft version

Adding a version, pins, toolchains and checks: [`BUILD.md`](../../../docs/BUILD.md).

A branched Stonecutter tree: `common`, `forge`, `neoforge` and `fabric` are branches, and each Minecraft
version a branch targets is a node, `:runtime:mc:modern:<branch>:<version>`. How the tree works, how to
add a version and what will bite: [`singlejar-logic/README.md`](../../../singlejar-logic/README.md)
§ *Many Minecraft versions*, beside the code that implements it (`ModernTree`, `ModernConventions`).

Every node compiles from the committed stub database unless it is made real (a run task, the active
IDE node, `-PcgRealNodes`, `-PcgStubs=false`); a node added or re-pinned needs the database regenerated —
[`STUBS.md`](../../../singlejar-logic/STUBS.md).

```bash
./gradlew checkAllTargets                                        # every node, every source set
./gradlew :runtime:mc:modern:<branch>:<version>:extractMcSources # that node's Minecraft, into versions/<version>/build/mc-src/
./gradlew :runtime:mc:modern:common:1.20.1:test                  # the Blaze3D mirror's override check (F5)
```

**This build goes first.** A project built on CrystalGraphics compiles each of its nodes against the
node here of the same version, so a version is added here before it is added there. The versions are
`singlejar { targets { } }` in `settings.gradle.kts`; a build that includes this one gets only the node
it targets (`BUILD.md` § *Layout*).
