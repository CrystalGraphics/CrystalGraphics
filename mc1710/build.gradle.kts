import java.util.zip.ZipFile

plugins {
    id("com.gtnewhorizons.gtnhconvention")
    `java-library`
}

group = providers.gradleProperty("modGroup").orElse("com").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()

apply(from = "repositories.gradle")
apply(from = "dependencies.gradle")

// NO MULTI-RELEASE OUTPUT. jvmDowngrader defaults to writing the original modern classes into
// META-INF/versions/<n>/ beside the downgraded ones, so a newer JVM picks the fast path. That is a fine
// default everywhere except here: FML's ModDiscoverer opens every classpath jar with
// asm-debug-all-5.0.3, which reads Java 8 class files and nothing later, and it walks EVERY entry --
// META-INF/versions/17/** included, even though the Java 8 run JVM would never load them. The result is
// a hard launch failure that names an innocent class:
//
//     There was a problem reading the entry META-INF/versions/17/com/crystalgraphics/api/
//     CgBindingPoints$Binding.class in the jar crystalgraphics-1.0.0-dev.jar - probably a corrupt zip
//
// GTNH's convention forces these on for 25 and 21 even when the property is set empty, which is why it
// has to be overridden on the extension rather than in gradle.properties. CrystalGUI's mc1710 has
// carried the same two lines (and the same discovery) since before this module needed them.
jvmdg.multiReleaseVersions.set(emptySet<JavaVersion>())
jvmdg.multiReleaseOriginal.set(false)


//// Remove Kotlin and a Java 9 file from JOML when shadowing.
//// Gradle pulls in a transitive dependency (Kotlin) and packages it for some reason.
//// This is for distributing the jar.
//// JOML is compiled for Java 8, so the fact I have to do this is stupid asf.
//// - Hussar
//// Sidenote, using the `-jdk8` published version, it might no longer try to pull that shit anymore but keeping it still
//tasks.shadowJar {
//    dependencies {
//        exclude(dependency("org.jetbrains.kotlin:.*"))
//    }
//
//    exclude("module-info.class")
//    exclude("kotlin/**")
//    exclude("org/jetbrains/kotlin/**")
//
//    // Include JNI binding subproject JAR (unpacked) in the shadow JAR.
//    // This is an implementation dep but cannot use shadowImplementation because the GTNH
//    // convention plugin requires RFG obfuscation variant attributes that plain Java
//    // subprojects don't publish (causes variant ambiguity errors).
//    dependsOn(":freetype-msdfgen-harfbuzz-bindings:jar")
//    // Platform split subprojects — same reasoning as above.
//    dependsOn(":core:jar")
//    dependsOn(":platform:jar")
//}

//// Resolve subproject jar output after evaluation (subproject tasks don't exist during
//// root project configuration). Unpacks the JAR into the shadow JAR.
//afterEvaluate {
//    tasks.shadowJar.configure {
//        val bindingsJar = project(":freetype-msdfgen-harfbuzz-bindings").tasks.named<Jar>("jar").get()
//        val coreJar = project(":core").tasks.named<Jar>("jar").get()
//        val platformJar = project(":platform").tasks.named<Jar>("jar").get()
//
//        from(zipTree(bindingsJar.archiveFile.get()))
//        from(zipTree(coreJar.archiveFile.get()))
//        from(zipTree(platformJar.archiveFile.get()))
//    }
//
//
//}


tasks.named<JavaExec>("runClient") {
    doFirst {
     //   val agent = findJarBySubstring("unimixins")
        // jvmArgs("-javaagent:${agent.absolutePath}")

       // val hotswapAgent = findJarBySubstring("hotswap-agent")
      //  jvmArgs("-javaagent:${hotswapAgent.absolutePath}")
    }
}


//
//// Ensure JNI native libraries from subproject are loadable during tests.
//// Strategy 2 (classpath extraction) works because subproject resources are on the
//// testRuntimeClasspath via implementation(project(...)). Strategy 3 (java.library.path)
//// is configured here as a fallback pointing to the platform-specific native directories.
//tasks.withType<Test> {
//    val nativesDir = project(":freetype-msdfgen-harfbuzz-bindings").file("src/main/resources/natives")
//    val os = System.getProperty("os.name", "").lowercase()
//    val arch = System.getProperty("os.arch", "").lowercase()
//    val osName = when {
//        os.contains("win") -> "windows"
//        os.contains("mac") || os.contains("darwin") -> "macos"
//        os.contains("linux") || os.contains("nux") -> "linux"
//        else -> null
//    }
//    val archName = when (arch) {
//        "amd64", "x86_64" -> "x64"
//        "aarch64", "arm64" -> "aarch64"
//        "x86", "i386", "i686" -> "x86"
//        else -> null
//    }
//    if (osName != null && archName != null) {
//        val platformDir = File(nativesDir, "$osName-$archName")
//        if (platformDir.isDirectory) {
//            systemProperty("java.library.path", platformDir.absolutePath)
//        }
//    }
//}

// ── The thin jar (J1) ────────────────────────────────────────────────────────────────────────────
//
// One input to the single-jar merge: this loader's own classes and resources at SRG names, and
// nothing else. `core`, `platform`, the font bindings, JOML, Jackson and the glTF loader enter the
// merge once at the root, so a copy here would ship four times over.
//
// ITS INPUT IS `jar`, NOT `shadowJar`. GTNH's `jar` is already exactly this module's output -- the
// `-dev-preshadow` artifact, 47 entries -- so a separate "assemble the thin contents" task would be
// a second spelling of a jar that already exists.
//
// AND NO DOWNGRADE STEP, which is the difference from the fat chain rather than an omission: the
// ROOT MERGE downgrades everything it assembles in one pass, so downgrading here would be the same
// work done twice. The consequence, measured: this jar's classes are major 69 -- the GTNH
// convention's modern-syntax path emits Java 25 bytecode and `downgradeJar` is what lowers it -- so
// the thin jar is production-MAPPED but not yet production-VERSIONED, and 52 is asserted at the root
// rather than here.
//
// It also means the merged jar is remapped before it is downgraded, where the per-loader fat jars
// downgrade first. jvmdg rewrites bytecode and does not read names, so the order is safe; what it
// costs is the supertype walk failing to resolve SRG-named Minecraft classes, which is the harmless
// wall of "Could not find class" the fat chain already documents.
val reobfThinJar = tasks.register<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfThinJar") {
    group = "build"
    description = "This loader's own classes at SRG names -- the merge's input."
    archiveClassifier.set("thin")

    // Every mapping input is TAKEN FROM `reobfJar` rather than re-derived: the two must reobfuscate
    // against the same SRG, the same CSVs and the same reference classpath, and a second spelling of
    // that configuration is a second thing to keep in step. Providers, so nothing resolves early.
    val fat = tasks.named<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfJar")
    inputJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    mcVersion.set(fat.flatMap { it.mcVersion })
    srg.set(fat.flatMap { it.srg })
    fieldCsv.set(fat.flatMap { it.fieldCsv })
    methodCsv.set(fat.flatMap { it.methodCsv })
    exceptorCfg.set(fat.flatMap { it.exceptorCfg })
    recompMcJar.set(fat.flatMap { it.recompMcJar })
    extraSrgEntries.set(fat.flatMap { it.extraSrgEntries })
    extraSrgFiles.from(fat.map { it.extraSrgFiles })
    referenceClasspath.from(fat.map { it.referenceClasspath })
}

// Written out here rather than reusing `cgbuildlogic.CheckThinJar`, which the three 1.20.x loaders
// share: that class lives in `mc1201/build-logic`, and this module applies the GTNH convention
// instead, so the class is not on its buildscript classpath.
//
// `com/crystalgraphics/platform/` IS NOT FORBIDDEN HERE, unlike in the 1.20.x check: the `:platform`
// SPI module and this module's own LWJGL2 service implementations share that package name, so no
// prefix can tell them apart. `core` is the payload that actually matters and it is caught.
val checkThinJar = tasks.register("checkThinJar") {
    group = "verification"
    description = "Fails unless the thin jar holds this loader alone."
    // 69 is Java 25, what this module compiles to before anything downgrades it. Asserted rather
    // than ignored so a toolchain change is visible here instead of at the root merge.
    val ceiling = 69
    val thin = reobfThinJar.flatMap { it.archiveFile }
    inputs.file(thin).withPropertyName("thinJar")
    outputs.upToDateWhen { true }
    doLast {
        val file = thin.get().asFile
        val forbidden = listOf(
            "com/crystalgraphics/core/", "com/crystalgraphics/api/", "com/crystalgraphics/gl/",
            "com/crystalgraphics/text/", "com/crystalgraphics/util/", "org/joml/", "com/fasterxml/",
            "de/javagl/", "natives/")
        val banned = mutableListOf<String>()
        val stray = mutableListOf<String>()
        val tooNew = mutableListOf<String>()
        var classes = 0
        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (forbidden.any { name.startsWith(it) }) banned += name
                if (!name.endsWith(".class")) continue
                classes++
                if (!name.startsWith("com/crystalgraphics/")) stray += name
                val head = ByteArray(8)
                zip.getInputStream(entry).use { it.read(head) }
                val major = ((head[6].toInt() and 0xFF) shl 8) or (head[7].toInt() and 0xFF)
                if (major > ceiling) tooNew += "$name (major $major)"
            }
        }
        if (banned.isNotEmpty() || stray.isNotEmpty() || tooNew.isNotEmpty()) {
            throw GradleException("${file.name} is not a thin jar."
                + banned.take(8).joinToString("\n      ", "\n  belongs to the root merge:\n      ", "")
                + stray.take(8).joinToString("\n      ", "\n  outside com/crystalgraphics/:\n      ", "")
                + tooNew.take(8).joinToString("\n      ", "\n  above major 52:\n      ", ""))
        }
        logger.lifecycle("[cg] {}: {} classes, all under com/crystalgraphics/ and at or below major {}",
                file.name, classes, ceiling)
    }
}

tasks.named("check") { dependsOn(checkThinJar) }
tasks.named("assemble") { dependsOn(reobfThinJar) }
