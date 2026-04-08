plugins {
    `java`
}

group = "io.github.somehussar.crystalgraphics"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    maven {
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    maven {
        name = "Minecraft Libraries"
        url = uri("https://libraries.minecraft.net/")
    }
    mavenCentral()
}

dependencies {
    implementation("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")
    implementation("org.lwjgl.lwjgl:lwjgl_util:2.9.4-nightly-20150209")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-windows")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-linux")
    runtimeOnly("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-osx")

    implementation(project(":freetype-harfbuzz-java-bindings"))
    implementation(project(":msdfgen-java-bindings"))

    // Root project for CgCapabilities, CgGlyphAtlas, font API
    implementation(project(":"))

    // JOML for Matrix4f (used by PoseStack and world-text projection math)
    implementation("org.joml:joml-jdk8:1.10.1")

    implementation("org.apache.logging.log4j:log4j-api:2.20.0")
    implementation("org.apache.logging.log4j:log4j-core:2.20.0")
    implementation("commons-io:commons-io:2.18.0")


    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// ── Extract LWJGL natives from platform JARs ─────────────────────────
// LWJGL 2 needs native DLLs on java.library.path (or org.lwjgl.librarypath).
// The natives-windows/linux/osx JARs contain .dll/.so/.dylib inside them;
// they must be extracted to a real directory before the JVM can load them.
val extractLwjglNatives by tasks.registering(Copy::class) {
    group = "harness"
    description = "Extract LWJGL native libraries from platform JARs"

    val nativeJars = configurations.runtimeClasspath.get().files.filter {
        it.name.startsWith("lwjgl-platform-") &&
                (it.name.contains("natives-windows") || it.name.contains("natives-linux") || it.name.contains("natives-osx"))
    }
    nativeJars.forEach { jar -> from(zipTree(jar)) }
    into(file("build/lwjgl-natives"))
    // Only extract actual native libraries, skip META-INF
    include("*.dll", "*.so", "*.dylib", "*.jnilib")
}

// ── runHarness task ────────────────────────────────────────────────────
// Launches the standalone debug harness outside Minecraft.
// Usage: ./gradlew.bat :gl-debug-harness:runHarness --args="--mode=triangle"
tasks.register<JavaExec>("runHarness") {
    group = "harness"
    description = "Run the standalone GL debug harness"
    dependsOn(extractLwjglNatives, ":patchedMcClasses", ":processPatchedMcResources")

    mainClass.set("io.github.somehussar.crystalgraphics.harness.FontDebugHarnessMain")

    // The harness runs outside Minecraft, but it still exercises Minecraft-mod
    // code paths. Add the root project's patched/deobfuscated Minecraft classes
    // so mod-side runtime references like ResourceLocation and reload-listener
    // interfaces are available during harness startup.
    val rootPatchedMcClasses = project(":").layout.buildDirectory.dir("classes/java/patchedMc")
    val rootPatchedMcResources = project(":").layout.buildDirectory.dir("resources/patchedMc")
    classpath = sourceSets["main"].runtimeClasspath + files(rootPatchedMcClasses, rootPatchedMcResources)

    // Collect all native library directories
    val nativePaths = mutableListOf<String>()

    // 1. Extracted LWJGL natives directory
    val lwjglNativesDir = file("build/lwjgl-natives").absolutePath
    nativePaths.add(lwjglNativesDir)

    // 2. JNI binding natives (freetype-harfbuzz, msdfgen)
    val os = System.getProperty("os.name", "").lowercase()
    val arch = System.getProperty("os.arch", "").lowercase()
    val osName = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        os.contains("linux") || os.contains("nux") -> "linux"
        else -> null
    }
    val archName = when (arch) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "aarch64"
        "x86", "i386", "i686" -> "x86"
        else -> null
    }
    if (osName != null && archName != null) {
        listOf(
            project(":freetype-harfbuzz-java-bindings").file("src/main/resources/natives/$osName-$archName"),
            project(":msdfgen-java-bindings").file("src/main/resources/natives/$osName-$archName")
        ).filter { it.isDirectory }.forEach { nativePaths.add(it.absolutePath) }
    }

    val existingLibPath = System.getProperty("java.library.path", "")
    val combinedLibPath = buildList {
        if (existingLibPath.isNotBlank()) add(existingLibPath)
        addAll(nativePaths)
    }.joinToString(File.pathSeparator)

    if (combinedLibPath.isNotEmpty()) {
        systemProperty("java.library.path", combinedLibPath)
    }

    // LWJGL 2 also checks org.lwjgl.librarypath for native loading
    systemProperty("org.lwjgl.librarypath", lwjglNativesDir)

    // Output directory for harness artifacts
    systemProperty("harness.output.dir", file("harness-output").absolutePath)

    // Shader hotswap: point to source resources so edits are picked up on R-key reload
    systemProperty("crystalgraphics.shader.resourceOverrideDir", 
        project(":").file("src/main/resources").absolutePath)

    // Forward system properties for native paths
    systemProperty("freetype.harfbuzz.native.path",
        System.getProperty("freetype.harfbuzz.native.path", ""))
}
