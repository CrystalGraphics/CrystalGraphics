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

    // ModDevGradle, which `useModernMinecraft` names -- and the one pin of it for every build using this
    // logic. The settings plugin loads these classes in the SETTINGS classloader, every project's
    // parent, so what they name must be visible there: compileOnly left real nodes unable to load
    // LegacyForgeExtension. A build-logic declaring its own copy gets this one anyway.
    implementation("net.neoforged:moddev-gradle:2.0.147")

    // SrgReobfJar composes Mojang's names with MCPConfig's SRG table -- the renamer ModDevGradle's
    // legacy mode uses, where that mode cannot reach (Forge 1.20.2-1.20.4).
    implementation("net.neoforged:srgutils:1.0.11")

    // The stub database reads class files and synthesizes them (StubDatabase, StubSignatures).
    implementation("org.ow2.asm:asm-tree:9.9")

    // compileOnly: the settings plugin builds Stonecutter trees, and every settings script that applies
    // it applies Stonecutter beside it, so the classes are on that classpath already.
    compileOnly("dev.kikugie:stonecutter:0.9.8")

    // Named rather than read from `dep.junit`: this is a standalone included build with its own
    // settings, so it has no root project to read a property from. Same version as everywhere else.
    testImplementation("junit:junit:4.13.2")
}

// The stub database every node's stub build compiles against (StubDatabase), from the `listStubInputs`
// each repo's real build wrote. Owned here; regenerated when a node is added or re-pinned.
// -PcgStubText also writes stubs/ as plain text, to read or diff.
tasks.register<JavaExec>("generateStubDatabase") {
    group = "stubs"
    description = "Writes stubs.zip: every node's platform API, deduplicated across nodes."
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("cgbuildlogic.StubDatabase")
    maxHeapSize = "6g"
    val graphics = projectDir.parentFile
    val roots = listOfNotNull(graphics, graphics.parentFile.takeIf { it.resolve("runtime/mc/modern").isDirectory })
    val text = if (providers.gradleProperty("cgStubText").isPresent) projectDir.resolve("stubs").absolutePath else "-"
    args(listOf(projectDir.resolve("stubs.zip").absolutePath, text) + roots.map { it.absolutePath })
}

// The settings plugin: a build's Minecraft nodes from the versions it targets. @see SingleJarSettings
gradlePlugin {
    plugins {
        create("singlejar") {
            id = "com.crystalgraphics.singlejar"
            implementationClass = "cgbuildlogic.SingleJarSettingsPlugin"
        }
    }
}

// The pin catalog's index, which Catalog reads: a jar cannot list its own resources.
val catalogIndex by tasks.registering {
    val catalog = layout.projectDirectory.dir("src/main/resources/cgbuildlogic/catalog")
    val out = layout.buildDirectory.dir("generated/catalog")
    inputs.dir(catalog).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(out)
    doLast {
        val root = catalog.asFile
        val index = root.walkTopDown().filter { it.isFile && it.name.endsWith(".properties") }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().joinToString("\n", postfix = "\n")
        out.get().file("cgbuildlogic/catalog/index.txt").asFile.apply { parentFile.mkdirs() }.writeText(index)
    }
}
sourceSets["main"].resources.srcDir(catalogIndex.map { it.outputs.files.singleFile })

// Where this build logic lives, so a build including it finds stubs.zip here (StubMode.stubDatabase):
// Gradle runs a build-logic jar from its own cache, so the classes cannot tell.
val buildLogicHome by tasks.registering {
    val home = projectDir.absolutePath
    val out = layout.buildDirectory.dir("generated/home")
    inputs.property("home", home)
    outputs.dir(out)
    doLast { out.get().file("cgbuildlogic/home.txt").asFile.apply { parentFile.mkdirs() }.writeText(home) }
}
sourceSets["main"].resources.srcDir(buildLogicHome.map { it.outputs.files.singleFile })
