plugins { id("cg-java17") }

// LWJGL 3.3.1 -- what MC 1.20.1 ships -- predates Java 21 and does not recognise its JNI version. It
// patches the JNIEnv function table on a guessed layout anyway; under a debugger's JVMTI agent that
// table is instrumented, so the write lands past it and the process dies with a native fail-fast
// (0xC0000409 on Windows) before the window opens.
//
// A dev run here is ALWAYS on Java 21: cg-java17 raises the toolchain to 21 so javac can read :core's
// v65 classes, and ModDevGradle takes the run JVM from the toolchain. So the client runs fine and
// cannot be debugged -- which reads as an IDE fault rather than a library one.
//
// 3.3.3 knows the version and uses the right layout. Dev runs only; nothing shipped resolves LWJGL.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.lwjgl") {
            useVersion("3.3.3")
            because("LWJGL 3.3.1 corrupts the JNIEnv table on Java 21 under a debugger")
        }
    }
}


repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForge" }
    maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
}

dependencies {
    // compileOnly: shadowJar bundles these manually (see each loader's build.gradle.kts).
    // runtimeOnly: picked up by Fabric/Loom dev runs via Gradle's standard runtimeClasspath.
    // ModDevGradle (Forge/NeoForge) dev runs ignore runtimeClasspath and instead use the
    // mods{} sourceSet declarations in each loader's build.gradle.kts.
    "compileOnly"(project(":mc1201:common"))
    "compileOnly"(project(":platform"))
    "compileOnly"(project(":core"))
    "compileOnly"(project(":freetype-msdfgen-harfbuzz-bindings"))
    "runtimeOnly"(project(":mc1201:common"))
    "runtimeOnly"(project(":platform"))
    "runtimeOnly"(project(":core"))
    // Fabric/Loom dev runs pick this up from runtimeClasspath.
    // ModDevGradle (Forge/NeoForge) dev runs need it in the mods{} sourceSet block instead.
    "runtimeOnly"(project(":freetype-msdfgen-harfbuzz-bindings"))
    // Mixin compileOnly — loaders bundle it at runtime
    "compileOnly"("org.spongepowered:mixin:${rootProject.properties["mc1201.mixin"]}")
    "annotationProcessor"("org.spongepowered:mixin:${rootProject.properties["mc1201.mixin"]}:processor")
    "compileOnly"("io.github.llamalad7:mixinextras-common:${rootProject.properties["mc1201.mixinextras"]}")
}
