# singlejar-logic — the single-jar build, shared

Build logic for shipping **one jar that installs on every Minecraft loader**. It lives in
CrystalGraphics because CrystalGraphics is the parent of everything that uses it and is always present
at runtime, and because a second copy of a build rule is a rule that gets fixed in one place only —
`CheckSingleJar` and `ModDescriptor` were 443 byte-identical lines in two repositories before this
directory existed.

**It is not CrystalGraphics-specific.** Nothing here names CrystalGraphics' packages, modules or mod
id; everything that varies between projects is a property a consumer sets.

## Wiring a project into it

Two lines of plumbing, wherever CrystalGraphics sits relative to you:

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("CrystalGraphics/singlejar-logic")   // or ../CrystalGraphics/singlejar-logic, etc.
}
```

```kotlin
// your own build-logic's build.gradle.kts
dependencies {
    implementation("com.crystalgraphics.build:singlejar-logic")
}
```

A composite build matches an included build to a dependency by **group and name**, so the path is the
only thing that changes per consumer.

## What is here, and what is deliberately not

| Shared | Why |
|---|---|
| `CheckSingleJar` | What the merged jar must be: the class-major ceiling, one copy of each relocated class, the manifest keys, the services. The rules are the same for every project; the expected values are properties |
| `ModDescriptor` | One declaration of a mod's identity printed into `fabric.mod.json`, `mods.toml`, `mcmod.info` and `pack.mcmeta`. Four formats, one source |

| Not shared | Why |
|---|---|
| The merge script itself | Each project's is genuinely different — its own relocation prefixes, library modules, bundled content and mod id. Measured: 56% of the lines differ, and the differences are exactly the per-project constants |
| The mixin config plugin | 69% differs; each names its own mixin configs and owner map |
| `ProdSmoke` | It drives clients carrying *every* mod, so one copy in the top consumer is right — not one per project |

The rule this directory is built on: **share the mechanism, never the constants.** Anything that
differs between two projects belongs to those projects.
