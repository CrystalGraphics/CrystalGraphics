// The controller of the legacy tree (Forge 1.8 to 1.12.2), and nothing else: which node is ACTIVE.
//
// As in runtime/mc/modern: the active node compiles straight from `forge/src/`, every other node from a
// copy Stonecutter generates, and switching rewrites the shared sources in place -- so switch back to
// the one below before committing.
plugins {
    id("dev.kikugie.stonecutter")
}
stonecutter active "1.12.2"
