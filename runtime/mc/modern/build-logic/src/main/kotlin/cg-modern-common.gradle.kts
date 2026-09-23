import cgbuildlogic.guardLoaderImports
import cgbuildlogic.useModernMinecraft
import cgbuildlogic.useNodeCoordinates

// ── A node of the `common` branch: vanilla Minecraft, and nothing from any loader ───────────────────
//
// Applied to every `:runtime:mc:modern:common:<version>`. How a node finds Minecraft (the toolchain is
// chosen by its own pins), its coordinates, and what it may not import are shared with every build laid
// out this way -- @see ../../../../../singlejar-logic, ModernTree and ModernConventions.

plugins {
    id("cg-java17")
    `maven-publish`
}

useNodeCoordinates()

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    // ADHOC: Mojang meta and MC libraries repos that net.neoforged.moddev.repositories (settings
    // plugin) should provide, but must be re-declared at project level in Gradle 9 due to
    // DependencyResolutionManagement ordering (same workaround the neoforge branch needs).
    maven("https://maven.neoforged.net/mojang-meta/") { name = "NeoForge Mojang Meta" }
    maven("https://libraries.minecraft.net/") {
        name = "MC Libraries"
        metadataSources { mavenPom() }
    }
}

useModernMinecraft()

dependencies {
    // api — platform is part of common's public API surface; loader modules
    // that depend on common will see platform types transitively at compile time.
    "api"(project(":platform"))
    // implementation — core is an internal dependency consumed by common.
    "implementation"(project(":core"))
    // api — tier 1 for this LWJGL family (§12). `api` rather than `implementation` because
    // `PlatformServiceModern` hands a `Lwjgl3GLContext` back through a public getter, so a loader
    // module reading it needs the type. One compiled copy serves every 1.13+ target; the merge adds
    // it once, which is why it is a `libraryProject` in cg-single-jar and not bundled per loader.
    "api"(project(":runtime:lwjgl:3"))
    // Mixin compileOnly — both loaders bundle it at runtime; never shade it.
    "compileOnly"("org.spongepowered:mixin:${property("modern.mixin")}")
    // NOTE: mixin annotationProcessor is intentionally omitted here — legacyForge configures
    // the Mixin AP with the correct SRG file automatically. Adding a second AP without SRG
    // causes duplicate-AP obfuscation-mapping errors for all @Inject targets.
    "compileOnly"("io.github.llamalad7:mixinextras-common:${property("modern.mixinextras")}")
    "annotationProcessor"("io.github.llamalad7:mixinextras-common:${property("modern.mixinextras")}")

    // For the tier-2 override check (F5). Reflection only -- no GL context and no game, which is the
    // whole point: the thing being asserted is which methods EXIST, and that is answerable statically.
    "testImplementation"("junit:junit:${property("dep.junit")}")
}

// Export compiled JAR so loader nodes can depend on it as a binary
configurations.create("commonOutput") {
    isCanBeConsumed = true; isCanBeResolved = false
}
artifacts { add("commonOutput", tasks.named("jar")) }

// A Forge import compiles on a legacyForge node and throws NoClassDefFoundError on the other two loaders.
guardLoaderImports()
