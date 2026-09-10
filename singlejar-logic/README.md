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
| `registerSingleJarPipeline` | The merge itself: thin jars in, services unioned, relocations, downgrade to 8, shade the stubs, one artifact, one check. A project passes a `SingleJarSpec` — its loaders, libraries, relocations, manifest and expectations — and nothing else |
| `registerDescriptorTasks` | One declaration of a mod's identity printed into `fabric.mod.json`, `mods.toml`, `mcmod.info` and `pack.mcmeta`, plus the check that the shipped per-loader files still agree with it |
| `CheckSingleJar` | What the merged jar must be: the class-major ceiling, one copy of each relocated class, the manifest keys, the services |
| `CheckThinJar` | That a loader's jar carries that loader and nothing the root merges |
| `ModDescriptor` | The model the four formats are printed from |

| Not shared | Why |
|---|---|
| The `plugins { }` block, group, version, jvmdg conventions | A precompiled script plugin cannot receive a `plugins` block; ~20 lines stay in each consumer |
| The mixin config plugin | 69% of its lines differ; each names its own mixin configs and owner map |
| `ProdSmoke` | It drives clients carrying *every* mod, so one copy in the top consumer is right — not one per project |
| **`mc-shared`** (`LoaderProbe`, `CrashVariant`) | **Deliberately duplicated.** It is runtime code shipped *inside* a jar, not build mechanism, and it answers "which jar am I?" — a per-jar question. Sharing it would make one mod's startup depend on another mod's build: a consumer running beside an older CrystalGraphics would die with `NoClassDefFoundError` in its entry-point constructor, turning a benign version skew into a hard crash. `LoaderProbe` also runs at **mixin bootstrap on 1.7.10**, before mods load, where resolving another mod's class is a bet. ~180 duplicated lines is the cheaper side of that trade |

The rule this directory is built on: **share the mechanism, never the constants.** Anything that
differs between two projects belongs to those projects.
