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
