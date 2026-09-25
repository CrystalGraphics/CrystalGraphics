import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { `kotlin-dsl` }

// The coordinates a consumer substitutes against. A composite build matches an included build to a
// dependency by group and name, so these two lines are the whole contract: any project that says
//
//     pluginManagement { includeBuild("<path>/CrystalGraphics/singlejar-logic") }
//     dependencies { implementation("com.crystalgraphics.build:singlejar-logic") }
//
// gets these classes, wherever CrystalGraphics sits relative to it.
group = "com.crystalgraphics.build"
version = "1.0.0"

repositories {
    gradlePluginPortal()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
}

dependencies {
    // Shadow and jvmDowngrader are the merge's own tools, and the shared tasks name their types.
    implementation("com.gradleup.shadow:shadow-gradle-plugin:9.2.2")
    implementation("xyz.wagyourtail.jvmdowngrader:gradle-plugin:1.3.5")

    // compileOnly: `useModernMinecraft` names ModDevGradle's extensions, and every build that calls it
    // already has ModDevGradle on its build-logic classpath. Carrying it here as well would be two
    // versions of one plugin for Gradle to pick between.
    compileOnly("net.neoforged:moddev-gradle:2.0.141")

    // SrgReobfJar composes Mojang's names with MCPConfig's SRG table -- the renamer ModDevGradle's
    // legacy mode uses, where that mode cannot reach (Forge 1.20.2-1.20.4).
    implementation("net.neoforged:srgutils:1.0.11")

    // The stub machinery reads and writes class files (StubClosure, StubSignatures).
    implementation("org.ow2.asm:asm-tree:9.9")

    // Named rather than read from `dep.junit`: this is a standalone included build with its own
    // settings, so it has no root project to read a property from. Same version as everywhere else.
    testImplementation("junit:junit:4.13.2")
}

// SigRecorder runs inside a node's javac, the oldest of which is 17; Kotlin follows, as Gradle requires
// both targets to agree.
java { targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
