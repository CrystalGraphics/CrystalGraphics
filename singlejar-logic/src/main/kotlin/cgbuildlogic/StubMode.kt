package cgbuildlogic

import org.gradle.StartParameter
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.FileCollection
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File
import java.util.jar.JarFile

/**
 * Stub mode: a node compiles against its slice of the stub database (`singlejar-logic/stubs.zip`,
 * [StubDatabase]) and applies no Minecraft toolchain — nothing downloaded, decompiled or remapped per
 * node. Real mode is today's build, and the one the database is generated from.
 *
 * ```
 * ./gradlew singleJar -PcgStubs                                   # every node in the database, stubbed
 * ./gradlew :runtime:mc:modern:forge:1.20.1:runClient -PcgStubs  # that node real: its task needs the game
 * ./gradlew :runtime:mc:modern:forge:1.20.1:checkStubEquivalence  # real vs stub, byte for byte
 * ./gradlew listStubInputs; ./gradlew -p CrystalGraphics listStubInputs
 * ./gradlew -p CrystalGraphics/singlejar-logic generateStubDatabase   # regenerate, after a node is added or re-pinned
 * ```
 *
 * A node is REAL, whatever `cgStubs` says, when:
 * - one of its own [REAL_TASKS] is requested, by path — in either build of the composite, so running
 *   CrystalGUI's node makes CrystalGraphics' node of the same loader and version real too — or
 *   `listStubInputs` is requested at all;
 * - it is Stonecutter's active version, so the IDE has the whole game where code is written;
 * - it is listed in `-PcgRealNodes=forge:1.20.1,1.21.1` (a bare version names every branch);
 * - the database has no entry for it.
 *
 * Every node convention calls [configureStubs] last. A branch script asks [stubMode] before touching a
 * toolchain, and renames its thin jars through [registerThinRename].
 */

/** Tasks that need a node's real game. */
val REAL_TASKS = setOf(
    "runClient", "runServer", "prepareClientRun", "prepareServerRun", "serverSmoke", "connectionProbe",
    "extractMcSources", "genSourcesWithVineflower", "checkStubEquivalence", "listStubInputs")

/** The compile tasks a stub serves, where the node has them. */
private val STUB_COMPILES = listOf("compileJava", "compileLangJava")

/** The stub database this build ships, in whichever repository holds `singlejar-logic`. */
val Project.stubDatabase: File?
    get() = listOf("singlejar-logic/stubs.zip", "CrystalGraphics/singlejar-logic/stubs.zip")
        .map { rootDir.resolve(it) }.firstOrNull { it.isFile }

/** This node's key in the database: `forge:1.20.1`. */
val Project.stubKey: String get() = "$modernLoader:$name"

/** Whether this node compiles against its stub. Decided once per build. */
val Project.stubMode: Boolean
    get() = extensions.extraProperties.let { extra ->
        if (!extra.has(STUB_MODE)) extra.set(STUB_MODE, stubsRequested && hasStub && !isRealNode)
        extra.get(STUB_MODE) as Boolean
    }

private const val STUB_MODE = "cg.stubMode"

private val Project.hasStub: Boolean get() = stubDatabase?.let { stubKey in StubDatabase.nodes(it) } ?: false

private val Project.stubsRequested: Boolean
    get() = (rootStartParameter.projectProperties["cgStubs"] ?: findProperty("cgStubs")?.toString())?.let { it != "false" } ?: false

private val Project.isRealNode: Boolean
    get() = requested(REAL_TASKS) || stonecutterActive || listedReal

private val Project.rootStartParameter: StartParameter
    get() {
        var g: Gradle = gradle
        while (g.parent != null) g = g.parent!!
        return g.startParameter
    }

/** Any of [names] requested on this node, in whichever build of the composite; `listStubInputs` anywhere. */
private fun Project.requested(names: Set<String>): Boolean =
    rootStartParameter.taskNames.any { task -> task == "listStubInputs" || names.any { task.endsWith("$path:$it") } }

private val Project.listedReal: Boolean
    get() = (rootStartParameter.projectProperties["cgRealNodes"] ?: findProperty("cgRealNodes")?.toString())
        ?.split(',')?.map { it.trim() }?.any { it == name || it == stubKey } ?: false

/** Stonecutter's `current.isActive`, read reflectively: its API is on the settings classpath, not ours. */
private val Project.stonecutterActive: Boolean
    get() {
        val stonecutter = extensions.findByName("stonecutter") ?: return false
        val current = stonecutter.javaClass.getMethod("getCurrent").invoke(stonecutter)
        return current.javaClass.getMethod("isActive").invoke(current) as Boolean
    }

/**
 * The jars a stub replaces: everything on this node's compile classpaths that no project builds or
 * brings — Minecraft, the loader and their libraries. Read off the compile tasks rather than the
 * configurations, since Unimined adds Minecraft to the source set's classpath directly.
 */
fun Project.stubTargets(): FileCollection {
    val configurations = listOf("compileClasspath", "langCompileClasspath").mapNotNull { configurations.findByName(it) }
    val ours = files(
        configurations.map { configuration ->
            configuration.incoming.artifactView { componentFilter { it is ProjectComponentIdentifier } }.files
        },
        extensions.getByType(SourceSetContainer::class.java).map { it.output },
        // What our own projects bring resolves in stub mode too, so it is not the stub's to replace.
        provider { configurations.flatMap(::broughtByProjects) })
    val classpaths = files(STUB_COMPILES.filter { it in tasks.names }.map { name ->
        tasks.named(name, JavaCompile::class.java).map { it.classpath }
    })
    return classpaths.minus(ours)
}

/** The external artifacts [configuration] reaches through a project dependency rather than directly. */
private fun broughtByProjects(configuration: Configuration): List<File> {
    val result = configuration.incoming.resolutionResult
    val owned = HashSet<ComponentIdentifier>()
    val seen = HashSet<ResolvedComponentResult>()
    val queue = ArrayDeque(result.allComponents.filter { it.id is ProjectComponentIdentifier && it != result.root })
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!seen.add(component)) continue
        for (dependency in component.dependencies.filterIsInstance<ResolvedDependencyResult>()) {
            if (dependency.selected.id !is ProjectComponentIdentifier) owned += dependency.selected.id
            queue += dependency.selected
        }
    }
    return configuration.incoming.artifacts.artifacts.filter { it.id.componentIdentifier in owned }.map { it.file }
}

/**
 * Stub mode: the node's stub jar on `compileOnly`. Real mode: `listStubInputs`, and
 * `checkStubEquivalence` where the database has the node. Call LAST in a node convention, after every
 * source set exists.
 */
fun Project.configureStubs() {
    if (stubMode) {
        dependencies.add("compileOnly", files(stubJarTask().flatMap { it.jar }))
        return
    }
    registerListStubInputs()
    if (!hasStub) return

    val stubJar = stubJarTask()
    tasks.register("checkStubEquivalence") {
        group = "stubs"
        description = "Fails unless this node's stub build is byte-identical to its real build."
    }
    for (name in STUB_COMPILES.filter { it in tasks.names }) {
        val real = tasks.named(name, JavaCompile::class.java)
        val stubCompile = tasks.register("stubCheck" + name.replaceFirstChar(Char::uppercaseChar), JavaCompile::class.java) {
            group = "stubs"
            val r = real.get()
            dependsOn(r)
            source = r.source
            classpath = r.classpath.minus(stubTargets()) + files(stubJar.flatMap { it.jar })
            javaCompiler.set(r.javaCompiler)
            sourceCompatibility = r.sourceCompatibility
            targetCompatibility = r.targetCompatibility
            options.release.set(r.options.release)
            options.encoding = r.options.encoding
            options.compilerArgs = r.options.compilerArgs
            options.compilerArgumentProviders.addAll(r.options.compilerArgumentProviders)
            options.annotationProcessorPath = r.options.annotationProcessorPath
            destinationDirectory.set(layout.buildDirectory.dir("stubs/check/$name"))
            options.generatedSourceOutputDirectory.set(layout.buildDirectory.dir("stubs/check/$name-generated"))
        }
        compareStubOutput(name, real.flatMap { it.destinationDirectory }, stubCompile.flatMap { it.destinationDirectory })
    }
}

/**
 * The step that puts [shadowTask]'s jar at the names its loader runs, in either mode — or [shadowTask]
 * itself where the loader runs Mojang's names (NeoForge, Forge 1.20.6+).
 *
 * ```kotlin
 * val thinJar = registerThinRename("thinShadowJar", "thin") {
 *     registerSrgReobf("thinShadowJar", "thin", sourceSets.main.get().compileClasspath)   // real mode only
 * }
 * registerThinRename("thinShadowJar", "thin", loomMappings = { loomTinyFile }) { tasks.register<RemapJarTask>(...) }
 * ```
 *
 * In stub mode [realRename] is never called, and the database's names do the rename. In real mode it is
 * the rename, which joins `checkStubEquivalence` and is what `listStubInputs` reads the full names from —
 * ModDevGradle's table on legacyForge, [SrgReobfJar]'s otherwise, [loomMappings] on Fabric.
 */
fun Project.registerThinRename(shadowTask: String, classifier: String, loomMappings: (() -> File)? = null,
                               realRename: () -> TaskProvider<out AbstractArchiveTask>): TaskProvider<out AbstractArchiveTask> {
    val format = when {
        modernLoader == "fabric" -> StubDatabase.Names.TINY
        modernLoader == "forge" && forgeRunsSrg(name) -> StubDatabase.Names.TSRG
        else -> return tasks.named(shadowTask, AbstractArchiveTask::class.java)
    }
    val libraries = extensions.getByType(SourceSetContainer::class.java).getByName("main").compileClasspath
    if (stubMode) {
        return if (format == StubDatabase.Names.TINY) registerStubRemap(shadowTask, classifier, libraries)
            else registerStubReobf(shadowTask, classifier, libraries)
    }
    val rename = realRename()
    registerRenameEquivalence(shadowTask, rename)
    val names: Provider<File> = when {
        format == StubDatabase.Names.TINY -> provider { (loomMappings ?: error("$path renames through Loom and names no Loom mappings"))() }
        usesLegacyForge -> layout.buildDirectory.file("moddev/artifacts/namedToIntermediate.tsrg").map { it.asFile }
        else -> rename.map { (it as SrgReobfJar).namesTable }
    }
    extensions.extraProperties.set(STUB_NAMES, format to names)
    if (format == StubDatabase.Names.TINY) {
        extensions.extraProperties.set(STUB_MANIFEST, rename.flatMap { it.archiveFile })
        tasks.named("listStubInputs").configure { dependsOn(rename) }
    }
    return rename
}

private const val STUB_NAMES = "cg.stubNames"
private const val STUB_MANIFEST = "cg.stubManifest"

/** A Forge node's SRG rename in stub mode: the renamer its real build runs, over the database's names. */
fun Project.registerStubReobf(shadowTask: String, classifier: String, libraries: FileCollection,
                              name: String = "reobf" + shadowTask.replaceFirstChar(Char::uppercaseChar)): TaskProvider<SrgReobfJar> {
    val (tool, args) = srgRenamer
    val source = tasks.named(shadowTask, AbstractArchiveTask::class.java)
    val minecraftVersion = property("mc.version").toString()
    val stubJar = stubJarTask()
    return tasks.register(name, SrgReobfJar::class.java) {
        group = "build"
        description = "$shadowTask renamed to SRG through the stub database's names."
        from(source.map { zipTree(it.archiveFile) })
        exclude("META-INF/MANIFEST.MF")
        minecraft.set(minecraftVersion)
        names.set(stubJar.flatMap { it.names })
        this.libraries.from(libraries)
        renamer.from(configurations.detachedConfiguration(dependencies.create(tool)))
        renamerArgs.set(args)
        archiveClassifier.set(classifier)
    }
}

/**
 * A Fabric node's intermediary rename in stub mode: tiny-remapper over the database's names, with the
 * `Fabric-*` manifest Loom would have written.
 */
fun Project.registerStubRemap(shadowTask: String, classifier: String, libraries: FileCollection,
                              name: String = thinJarTask(this, shadowTask)): TaskProvider<TinyRemapJar> {
    val source = tasks.named(shadowTask, AbstractArchiveTask::class.java)
    val attributes = StubDatabase.manifest(stubDatabase!!, stubKey) + (FABRIC_GRADLE_VERSION to gradle.gradleVersion)
    val stubJar = stubJarTask()
    return tasks.register(name, TinyRemapJar::class.java) {
        group = "build"
        description = "$shadowTask renamed to intermediary through the stub database's names."
        from(source.map { zipTree(it.archiveFile) })
        exclude("META-INF/MANIFEST.MF")
        mappings.set(stubJar.flatMap { it.names })
        this.libraries.from(libraries)
        remapper.from(configurations.detachedConfiguration(dependencies.create(TINY_REMAPPER)))
        manifest.attributes(attributes.toMap())
        archiveClassifier.set(classifier)
    }
}

/** Loom writes it; a stub build knows it, so the database does not carry it. */
const val FABRIC_GRADLE_VERSION = "Fabric-Gradle-Version"

/**
 * Real mode, a node in the database: [real] — the toolchain's rename of [shadowTask] — against the same
 * rename run as a stub build runs it, over the same input. Joins `checkStubEquivalence`.
 */
private fun Project.registerRenameEquivalence(shadowTask: String, real: TaskProvider<out AbstractArchiveTask>) {
    if (!hasStub) return
    val libraries = extensions.getByType(SourceSetContainer::class.java).getByName("main").compileClasspath
        .minus(stubTargets()) + files(stubJarTask().flatMap { it.jar })
    val checkName = "stubCheck" + real.name.replaceFirstChar(Char::uppercaseChar)
    val stub: TaskProvider<out AbstractArchiveTask> = if (modernLoader == "fabric") registerStubRemap(shadowTask, "", libraries, checkName)
        else registerStubReobf(shadowTask, "", libraries, checkName)
    stub.configure { destinationDirectory.set(layout.buildDirectory.dir("stubs/check/$shadowTask")) }
    compareStubOutput(real.name, real.flatMap { it.archiveFile }, stub.flatMap { it.archiveFile })
}

private fun Project.compareStubOutput(what: String, expected: Any, actual: Any) {
    val compare = tasks.register("checkStubEquivalence" + what.replaceFirstChar(Char::uppercaseChar), CompareOutputs::class.java) {
        group = "stubs"
        description = "$what: the real build against the stub build."
        this.expected.from(expected)
        this.actual.from(actual)
    }
    tasks.named("checkStubEquivalence").configure { dependsOn(compare) }
}

private fun Project.stubJarTask(): TaskProvider<StubJar> =
    if ("stubJar" in tasks.names) tasks.named("stubJar", StubJar::class.java)
    else tasks.register("stubJar", StubJar::class.java) {
        group = "stubs"
        description = "This node's slice of the stub database: the class files it compiles against, and its names."
        database.set(stubDatabase)
        node.set(stubKey)
        jar.set(layout.buildDirectory.file("stubs/stub.jar"))
        names.set(layout.buildDirectory.file("stubs/stub.names"))
    }

/**
 * Real mode: writes `build/stubs/inputs.txt` for [StubDatabase] — `target <path>` per jar the stub
 * replaces, `names <format> <path>` for the full rename table, `manifest <key> <value>` per `Fabric-*`
 * attribute Loom writes. Resolves, and builds nothing but the Fabric rename it reads the manifest of.
 */
private fun Project.registerListStubInputs() {
    val targets = stubTargets()
    val out = layout.buildDirectory.file("stubs/inputs.txt")
    val extra = extensions.extraProperties
    tasks.register("listStubInputs") {
        group = "stubs"
        description = "Lists what this node's toolchain supplies, for the stub database."
        outputs.upToDateWhen { false }
        doLast {
            @Suppress("UNCHECKED_CAST")
            val names = (if (extra.has(STUB_NAMES)) extra.get(STUB_NAMES) as Pair<StubDatabase.Names, Provider<File>> else null)
                ?.let { (format, file) -> listOf("names $format ${file.get()}") }.orEmpty()
            @Suppress("UNCHECKED_CAST")
            val manifest = (if (extra.has(STUB_MANIFEST)) extra.get(STUB_MANIFEST) as Provider<*> else null)
                ?.let { jar -> JarFile(file(jar.get())).use { it.manifest.mainAttributes.entries.toList() } }
                ?.map { it.key.toString() to it.value.toString() }
                ?.filter { (key, _) -> key.startsWith("Fabric-") && key != FABRIC_GRADLE_VERSION }
                ?.map { (key, value) -> "manifest $key $value" }.orEmpty()
            out.get().asFile.apply { parentFile.mkdirs() }.writeText(
                (targets.files.map { "target $it" } + names + manifest).joinToString("\n", postfix = "\n"))
        }
    }
}
