plugins {
    `java-library`
}

group = "com.crystalgraphics"
version = rootProject.version.toString()

// Version shorthands from gradle.properties
val lombokVer  = rootProject.properties["dep.lombok"].toString()
val jomlVer    = rootProject.properties["dep.joml"].toString()
val lwjglVer   = rootProject.properties["dep.lwjgl"].toString()
val log4jVer   = rootProject.properties["dep.log4j"].toString()
val jabelVer   = rootProject.properties["dep.jabel"].toString()
val junitVer   = rootProject.properties["dep.junit"].toString()
val jdkVersion = rootProject.properties["dep.jdk.toolchain"].toString().toInt()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(jdkVersion))
    }
    // No sourceCompatibility/targetCompatibility — toolchain handles version selection.
    // IntelliJ reads the toolchain JDK (17) as the module language level.
}

// No idea { module { languageLevel } } needed — IntelliJ infers language level 17
// from the toolchain automatically, and we don't set options.release (see below).


repositories {
    maven {
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    maven {
        // LWJGL 2.9.4-nightly is distributed via Minecraft's CDN, not Maven Central.
        name = "Minecraft Libraries"
        url = uri("https://libraries.minecraft.net/")
    }
    mavenCentral()
}

dependencies {

    // JNI bindings subproject — needed for FreeType/HarfBuzz/MSDFgen classes used in text pipeline.
    compileOnly(project(":freetype-msdfgen-harfbuzz-bindings"))

    // @Nullable / @NonNull annotations — javax.annotation not on module path in JDK 17+
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    // Mesh loaders — shadowed into root jar, needed here for compilation only
    compileOnly("de.javagl:obj:0.4.0")
    compileOnly("de.javagl:jgltf-model:2.0.4")

    // commons-io — bundled in MC 1.7.10; not available in core/ toolchain classpath
    compileOnly("commons-io:commons-io:2.4")

    // HotSwap agent — dev-only runtime class reloading, compileOnly in root too
    compileOnly("org.hotswapagent:hotswap-agent-core:1.4.1")

    compileOnly("org.lwjgl.lwjgl:lwjgl:$lwjglVer")
    compileOnly("org.joml:joml-jdk8:$jomlVer")

    compileOnly("org.projectlombok:lombok:$lombokVer")
    annotationProcessor("org.projectlombok:lombok:$lombokVer")

    // Jabel: backports modern Java syntax (records, etc.) to Java 8 bytecode.
    // Must run as annotation processor on a JDK 11+ toolchain with --release 8.
    annotationProcessor("com.github.bsideup.jabel:jabel-javac-plugin:$jabelVer")
    compileOnly("com.github.bsideup.jabel:jabel-javac-plugin:$jabelVer")

    implementation(project(":platform"))
    compileOnly("org.apache.logging.log4j:log4j-api:$log4jVer")

    testImplementation("junit:junit:$junitVer")
    testImplementation("org.lwjgl.lwjgl:lwjgl:$lwjglVer")
    testImplementation("org.joml:joml-jdk8:$jomlVer")
    testImplementation(project(":freetype-msdfgen-harfbuzz-bindings"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Add --release 8 via compilerArgs instead of options.release.set(8).
    //
    // Why compilerArgs and not options.release:
    //   IntelliJ's Gradle import reads JavaCompile.options.release (the typed Gradle
    //   Property<Integer>) to determine the module language level. It does NOT scan
    //   compilerArgs. So with options.release unset + toolchain=17, IntelliJ sees
    //   language level 17 (from toolchain) and records/sealed/etc work in the editor.
    //
    // Why --release 8 is still needed:
    //   Jabel needs --release 8 to activate its AST patching and to force javac to
    //   output Java 8 bytecode. Without it the class files target Java 17.
    //
    // Why this doesn't conflict with -source/-target:
    //   With a toolchain configured, Gradle does NOT add -source or -target to the
    //   javac invocation. So --release 8 arrives alone — no conflict.
    options.compilerArgs.addAll(listOf("--release", "8"))
    // Jabel uses ByteBuddy which only officially supports up to JDK 20.
    // Force experimental mode so it works with JDK 21+ toolchain.
    options.isFork = true
    options.forkOptions.jvmArgs!!.add("-Dnet.bytebuddy.experimental=true")
}

// Compile-time guardrail: fail if any MC imports sneak into core/
// Checks import statements only — ignores comments and string literals.
// Uses a plain String path captured at configuration time so this block is
// compatible with Gradle configuration cache (no project-script object captured).
tasks.named<JavaCompile>("compileJava") {
    val srcRoot: String = layout.projectDirectory.dir("src/main/java").asFile.absolutePath
    doLast {
        val violations = File(srcRoot).walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .filter { f ->
                f.readLines().any { line ->
                    val trimmed = line.trimStart()
                    trimmed.startsWith("import ") && (
                        trimmed.contains("net.minecraft") ||
                        trimmed.contains("cpw.mods.fml") ||
                        trimmed.contains("net.minecraftforge")
                    )
                }
            }
            .toList()
        if (violations.isNotEmpty()) error("MC imports found in core/: ${violations.map { it.name }}")
    }
}
