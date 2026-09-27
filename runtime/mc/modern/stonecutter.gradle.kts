// The controller of the 1.20.x tree, and nothing else: which node is ACTIVE.
//
// The active node compiles straight from each branch's `src/`; every other node compiles from a copy
// Stonecutter generates under its own build directory. Switching the active version REWRITES the
// shared sources in place, so switch back to the one below before committing, or the diff carries
// directive noise rather than the change.
plugins {
    id("dev.kikugie.stonecutter")
}
stonecutter active "1.20.1"

// Renames Minecraft made, applied to every node from the version that made them rather than as a
// directive at every use. The sources stay at the active version's spelling.
stonecutter parameters {
    // 1.21.9: Window's GLFW handle.
    replacements.string(current.parsed >= "1.21.9") {
        replace("getWindow().getWindow()", "getWindow().handle()")
    }
    // 1.21.11: ResourceLocation became Identifier, package unchanged.
    replacements.string(current.parsed >= "1.21.11") {
        replace("ResourceLocation", "Identifier")
    }
}
