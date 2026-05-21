plugins {
    id("cg-java17")
    id("net.neoforged.moddev.legacyforge") // provides MC 1.20.1 as compileOnly via legacyForge
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    // ADHOC: Mojang meta and MC libraries repos that net.neoforged.moddev.repositories (settings
    // plugin) should provide, but must be re-declared at project level in Gradle 9 due to
    // DependencyResolutionManagement ordering (same workaround used in mc1201/neoforge).
    maven("https://maven.neoforged.net/mojang-meta/") { name = "NeoForge Mojang Meta" }
    maven("https://libraries.minecraft.net/") {
        name = "MC Libraries"
        metadataSources { mavenPom() }
    }
}

dependencies {
    // api — platform is part of common's public API surface; loader modules
    // that depend on common will see platform types transitively at compile time.
    "api"(project(":platform"))
    // implementation — core is an internal dependency consumed by common.
    "implementation"(project(":core"))
    // Mixin compileOnly — both loaders bundle it at runtime; never shade it.
    "compileOnly"("org.spongepowered:mixin:${rootProject.properties["mc1201.mixin"]}")
    // NOTE: mixin annotationProcessor is intentionally omitted here — legacyForge configures
    // the Mixin AP with the correct SRG file automatically. Adding a second AP without SRG
    // causes duplicate-AP obfuscation-mapping errors for all @Inject targets.
    "compileOnly"("io.github.llamalad7:mixinextras-common:${rootProject.properties["mc1201.mixinextras"]}")
    "annotationProcessor"("io.github.llamalad7:mixinextras-common:${rootProject.properties["mc1201.mixinextras"]}")
}

// Export compiled JAR so loader subprojects can depend on it as a binary
configurations.create("commonOutput") {
    isCanBeConsumed = true; isCanBeResolved = false
}
artifacts { add("commonOutput", tasks.named("jar")) }

// LegacyForge mode: puts MC 1.20.1 + MinecraftForge (compileOnly) on the classpath.
// NeoForm 1.20.1 was never published to Maven, so neoFormRuntime{}/neoForge{neoFormVersion=...}
// cannot be used. Using legacyForge is the only ModDevGradle path that resolves MC 1.20.1.
// The Forge-specific classes are compileOnly; they never appear in the common JAR or runtime.
// Do NOT use neoForge{} here — there is no NeoForge artifact for MC 1.20.1.
legacyForge {
    // MinecraftForge artifact ID format: "<mcVersion>-<forgeVersion>"
    version = "1.20.1-${rootProject.properties["mc1201.forge"]}"
}
