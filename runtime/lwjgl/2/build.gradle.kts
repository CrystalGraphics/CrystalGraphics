import java.io.File as JFile

// runtime/lwjgl/2 — §12 tier 1 for LWJGL2: the GL backend, the context and the input service,
// written against LWJGL 2.9.4 and nothing else.
//
// One copy in the merged jar, compiled once, never remapped, shared by every LWJGL2 target (1.7.10
// and 1.12.2) and by the debug harness. A class that needs one value from a Minecraft-facing tier
// takes it as a constructor argument; a class that needs a Minecraft *call* is a T2 subclass in the
// target's own module — `OpenGlHelperGLBackend` is the only one, and it overrides one method.
//
// JAVA 17, like `platform` and `core` here and `runtime/mc/modern/common` next door. NOT Java 8 as `mc-shared`
// is: Gradle matches a JVM-version attribute at resolution, so an 8 consumer cannot see a 17 producer
// at all and the build fails on the classpath rather than on a class. `mc-shared` gets away with 8
// because it depends on nothing of ours. The merged jar's major-52 ceiling — FML 1.7.10 reads every
// entry with asm-debug-all-5.0.3 — is met by `downgradeSingleJar` rewriting the whole jar on the way
// in, so paying it here as well buys nothing.

plugins {
    `java-library`
}

group = providers.gradleProperty("modGroup").orElse("com.crystalgraphics").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()
base { archivesName.set("crystalgraphics-lwjgl2") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    // LWJGL 2.9.4-nightly is Mojang's own build of it and lives only in their library repo --
    // Central has no such version. Same source the harness and the GTNH toolchain resolve it from.
    maven("https://libraries.minecraft.net/") { name = "MinecraftLibraries" }
    mavenCentral()
}

dependencies {
    // The SPI this tier implements. compileOnly: the merge adds `platform` once.
    compileOnly(project(":platform"))

    // compileOnly because the game supplies LWJGL — bundling it would put a second copy of
    // org.lwjgl on a classpath that already has the one the loader booted with.
    compileOnly("org.lwjgl.lwjgl:lwjgl:${providers.gradleProperty("dep.lwjgl").getOrElse("2.9.4-nightly-20150209")}")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// The tier boundary, enforced rather than described (F4). Everything above LWJGL is what the
// subclasses in `1.7.10`/`1.12.2` are for.
tasks.named<JavaCompile>("compileJava") {
    val srcRoot: String = layout.projectDirectory.dir("src/main/java").asFile.absolutePath
    doLast {
        val violations = JFile(srcRoot).walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .filter { f ->
                f.readLines().any { line ->
                    val trimmed = line.trimStart()
                    trimmed.startsWith("import ") && (
                        trimmed.contains("net.minecraft") ||
                        trimmed.contains("com.mojang") ||
                        trimmed.contains("cpw.mods.fml") ||
                        trimmed.contains("net.minecraftforge") ||
                        trimmed.contains("net.neoforged") ||
                        trimmed.contains("net.fabricmc")
                    )
                }
            }
            .toList()
        if (violations.isNotEmpty()) {
            error("Minecraft or loader imports found in runtime/lwjgl/2/ - tier 1 is LWJGL and " +
                "the JDK only:\n" +
                violations.joinToString("\n") { "  ${it.relativeTo(JFile(srcRoot))}" })
        }
    }
}
