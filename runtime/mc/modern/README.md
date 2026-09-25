# runtime/mc/modern — one source tree, a node per Minecraft version

A branched Stonecutter tree: `common`, `forge`, `neoforge` and `fabric` are branches, and each Minecraft
version a branch targets is a node, `:runtime:mc:modern:<branch>:<version>`. How the tree works, how to
add a version and what will bite: [`singlejar-logic/README.md`](../../../singlejar-logic/README.md)
§ *Many Minecraft versions*, beside the code that implements it (`ModernTree`, `ModernConventions`).

Every node but the active one can compile from the committed stub database instead of its real
toolchain (`-PcgStubs`); a node added or re-pinned needs it regenerated — [`STUBS.md`](../../../singlejar-logic/STUBS.md).

```bash
./gradlew checkAllTargets                                # every node, every source set
./gradlew :runtime:mc:modern:forge:1.20.1:runClient
./gradlew :runtime:mc:modern:common:1.20.1:test          # the Blaze3D mirror's override check (F5)
```

**This build goes first.** A project built on CrystalGraphics compiles each of its common nodes
against the common node here of the same version, so a version is added here before it is added there.
The node table is `modernNodes` in `settings.gradle.kts`; common and forge at 1.20.1 are present even
when a consumer includes this build for its libraries alone.
