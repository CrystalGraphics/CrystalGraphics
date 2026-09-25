package cgbuildlogic

import org.gradle.StartParameter
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.FileCollection
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File
import java.net.JarURLConnection

/**
 * Stub mode: a node compiles against its committed `stub.sig` and applies no Minecraft toolchain —
 * nothing downloaded, decompiled or remapped per node. Real mode is today's build, and the one a
 * stub is generated from.
 *
 * ```
 * ./gradlew singleJar -PcgStubs                                   # every node with a stub.sig, stubbed
 * ./gradlew :runtime:mc:modern:forge:1.20.1:runClient -PcgStubs  # that node real: its task needs the game
 * ./gradlew :runtime:mc:modern:forge:1.20.1:generateStubs         # rewrites its stub.sig + stub.tsrg
 * ./gradlew :runtime:mc:modern:forge:1.20.1:checkStubEquivalence  # real vs stub, byte for byte
 * ```
 *
 * A node is REAL, whatever `cgStubs` says, when:
 * - one of its own [REAL_TASKS] is requested, by path — in either build of the composite, so running
 *   CrystalGUI's node makes CrystalGraphics' node of the same loader and version real too;
 * - it is Stonecutter's active version, so the IDE has the whole game where code is written;
 * - it is listed in `-PcgRealNodes=forge:1.20.1,1.21.1` (a bare version names every branch);
 * - it has no `stub.sig`;
 * - it is a `common` node, and stub maintenance is requested on any node of its version: a loader's
 *   stub is closed over its common node's references too.
 *
 * Every node convention calls [configureStubs] last. A branch script asks [stubMode] before touching a
 * toolchain, and renames its thin jars through [registerThinRename].
 */

/** Tasks that need a node's real game. */
val REAL_TASKS = setOf(
    "runClient", "runServer", "prepareClientRun", "prepareServerRun", "serverSmoke", "connectionProbe",
    "extractMcSources", "genSourcesWithVineflower", "generateStubs", "checkStubEquivalence")

private val MAINTENANCE_TASKS = setOf("generateStubs", "checkStubEquivalence")

/** The compile tasks a stub serves, where the node has them. */
private val STUB_COMPILES = listOf("compileJava", "compileLangJava")

/** This node's committed stub. */
val Project.stubSignatureFile: File get() = projectDir.resolve("stub.sig")

/** The rename table committed beside it, for a node whose loader runs other names. */
fun Project.stubNamesFile(format: StubMappings.Format): File = projectDir.resolve("stub.${format.extension}")

/** Whether this node compiles against its stub. Decided once per build. */
val Project.stubMode: Boolean
    get() = extensions.extraProperties.let { extra ->
        if (!extra.has(STUB_MODE)) extra.set(STUB_MODE, stubsRequested && stubSignatureFile.isFile && !isRealNode)
        extra.get(STUB_MODE) as Boolean
    }

private const val STUB_MODE = "cg.stubMode"

private val Project.stubsRequested: Boolean
    get() = (rootStartParameter.projectProperties["cgStubs"] ?: findProperty("cgStubs")?.toString())?.let { it != "false" } ?: false

private val Project.isRealNode: Boolean
    get() = requested(REAL_TASKS) || stonecutterActive || listedReal
        || (modernLoader == "common" && (listOf("common") + MODERN_LOADERS).any { requested(MAINTENANCE_TASKS, sameVersionNodePath(it)) })

/** Whether this node records what javac resolves, for `generateStubs` here or on a loader of its version. */
private val Project.recordsStubReferences: Boolean
    get() = !stubMode && (requested(setOf("generateStubs"))
        || (modernLoader == "common" && MODERN_LOADERS.any { requested(setOf("generateStubs"), sameVersionNodePath(it)) }))

private val Project.rootStartParameter: StartParameter
    get() {
        var g: Gradle = gradle
        while (g.parent != null) g = g.parent!!
        return g.startParameter
    }

/** Any of [names] requested on [nodePath], in whichever build of the composite. */
private fun Project.requested(names: Set<String>, nodePath: String = path): Boolean =
    rootStartParameter.taskNames.any { task -> names.any { task.endsWith("$nodePath:$it") } }

private val Project.listedReal: Boolean
    get() = (rootStartParameter.projectProperties["cgRealNodes"] ?: findProperty("cgRealNodes")?.toString())
        ?.split(',')?.map { it.trim() }?.any { it == name || it == "$modernLoader:$name" } ?: false

/** Stonecutter's `current.isActive`, read reflectively: its API is on the settings classpath, not ours. */
private val Project.stonecutterActive: Boolean
    get() {
        val stonecutter = extensions.findByName("stonecutter") ?: return false
        val current = stonecutter.javaClass.getMethod("getCurrent").invoke(stonecutter)
        return current.javaClass.getMethod("isActive").invoke(current) as Boolean
    }

/**
 * The jars a stub replaces: everything on this node's compile classpaths that no project builds —
 * Minecraft, the loader and every library they bring. Read off the compile tasks rather than the
 * configurations, since Unimined adds Minecraft to the source set's classpath directly.
 */
fun Project.stubTargets(): FileCollection {
    val ours = files(
        listOf("compileClasspath", "langCompileClasspath").mapNotNull { configurations.findByName(it) }.map { configuration ->
            configuration.incoming.artifactView { componentFilter { it is ProjectComponentIdentifier } }.files
        },
        extensions.getByType(SourceSetContainer::class.java).map { it.output })
    val classpaths = files(STUB_COMPILES.filter { it in tasks.names }.map { name ->
        tasks.named(name, JavaCompile::class.java).map { it.classpath }
    })
    return classpaths.minus(ours)
}

/**
 * Stub mode: the stub jar on `compileOnly`. Real mode: `generateStubs`, and `checkStubEquivalence` where
 * a stub is committed. Call LAST in a node convention, after every source set exists.
 */
fun Project.configureStubs() {
    if (stubMode) {
        dependencies.add("compileOnly", files(stubJarTask().flatMap { it.jar }))
        return
    }
    val compiles = STUB_COMPILES.filter { it in tasks.names }
    if (recordsStubReferences) recordReferences(compiles)

    val loaderCommon = if (modernLoader == "common") null else commonNode
    tasks.register("generateStubs", GenerateStubs::class.java) {
        group = "stubs"
        description = "Writes this node's stub.sig from a real build: what it compiles against, and nothing else."
        compiles.forEach { dependsOn(it) }
        records.from(fileTree(layout.buildDirectory.dir("stubs/records")))
        if (loaderCommon != null) {
            dependsOn(loaderCommon.tasks.matching { it.name in STUB_COMPILES })
            records.from(loaderCommon.fileTree(loaderCommon.layout.buildDirectory.dir("stubs/records")))
        }
        classpath.from(compiles.map { name -> tasks.named(name, JavaCompile::class.java).map { it.classpath } })
        targets.from(stubTargets())
        signatures.set(stubSignatureFile)
    }

    if (!stubSignatureFile.isFile) return
    val stubJar = stubJarTask()
    stubJar.configure { mustRunAfter("generateStubs") }
    val check = tasks.register("checkStubEquivalence") {
        group = "stubs"
        description = "Fails unless this node's stub build is byte-identical to its real build."
    }
    for (name in compiles) {
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
            options.compilerArgs = r.options.compilerArgs.filterNot { it.startsWith(RECORDER_ARG) }
            options.compilerArgumentProviders.addAll(r.options.compilerArgumentProviders)
            options.annotationProcessorPath = r.options.annotationProcessorPath?.minus(files(recorderPath))
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
 * In stub mode [realRename] is never called, and the committed names do the rename. In real mode it is
 * the rename, which joins `checkStubEquivalence` and is what `generateStubs` cuts the committed names
 * from — ModDevGradle's table on legacyForge, [SrgReobfJar]'s otherwise, [loomMappings] on Fabric.
 */
fun Project.registerThinRename(shadowTask: String, classifier: String, loomMappings: (() -> File)? = null,
                               realRename: () -> TaskProvider<out AbstractArchiveTask>): TaskProvider<out AbstractArchiveTask> {
    val format = when {
        modernLoader == "fabric" -> StubMappings.Format.TINY
        modernLoader == "forge" && forgeRunsSrg(name) -> StubMappings.Format.TSRG
        else -> return tasks.named(shadowTask, AbstractArchiveTask::class.java)
    }
    val libraries = extensions.getByType(SourceSetContainer::class.java).getByName("main").compileClasspath
    if (stubMode) {
        return if (format == StubMappings.Format.TINY) registerStubRemap(shadowTask, classifier, libraries)
            else registerStubReobf(shadowTask, classifier, libraries)
    }
    val rename = realRename()
    registerRenameEquivalence(shadowTask, rename)
    tasks.named("generateStubs", GenerateStubs::class.java).configure {
        mappingFormat.set(format)
        names.set(stubNamesFile(format))
        when {
            format == StubMappings.Format.TINY -> {
                val mappings = loomMappings ?: throw GradleException("$path renames through Loom and names no Loom mappings")
                fullMappings.fileProvider(provider { mappings() })
                manifestFrom.set(rename.flatMap { it.archiveFile })
            }
            usesLegacyForge -> {
                fullMappings.set(layout.buildDirectory.file("moddev/artifacts/namedToIntermediate.tsrg"))
                dependsOn("createMinecraftArtifacts")
            }
            else -> {
                fullMappings.fileProvider(rename.map { (it as SrgReobfJar).namesTable })
                dependsOn(rename)
            }
        }
    }
    return rename
}

/**
 * A Forge node's SRG rename in stub mode: the renamer its real build runs, over the committed
 * `stub.tsrg`. Takes `reobf<ShadowTask>`, the name the real build's rename has.
 */
fun Project.registerStubReobf(shadowTask: String, classifier: String, libraries: FileCollection,
                              name: String = "reobf" + shadowTask.replaceFirstChar(Char::uppercaseChar)): TaskProvider<SrgReobfJar> {
    val (tool, args) = srgRenamer
    val source = tasks.named(shadowTask, AbstractArchiveTask::class.java)
    val minecraftVersion = property("mc.version").toString()
    return tasks.register(name, SrgReobfJar::class.java) {
        group = "build"
        description = "$shadowTask renamed to SRG through the committed stub.tsrg."
        from(source.map { zipTree(it.archiveFile) })
        exclude("META-INF/MANIFEST.MF")
        minecraft.set(minecraftVersion)
        names.set(stubNamesFile(StubMappings.Format.TSRG))
        this.libraries.from(libraries)
        renamer.from(configurations.detachedConfiguration(dependencies.create(tool)))
        renamerArgs.set(args)
        archiveClassifier.set(classifier)
    }
}

/**
 * A Fabric node's intermediary rename in stub mode: tiny-remapper over the committed `stub.tiny`, with
 * the `Fabric-*` manifest Loom would have written. Takes the name the real build's rename has.
 */
fun Project.registerStubRemap(shadowTask: String, classifier: String, libraries: FileCollection,
                              name: String = thinJarTask(this, shadowTask)): TaskProvider<TinyRemapJar> {
    val source = tasks.named(shadowTask, AbstractArchiveTask::class.java)
    val attributes = StubSignatures.manifest(stubSignatureFile) + (GenerateStubs.FABRIC_GRADLE_VERSION to gradle.gradleVersion)
    return tasks.register(name, TinyRemapJar::class.java) {
        group = "build"
        description = "$shadowTask renamed to intermediary through the committed stub.tiny."
        from(source.map { zipTree(it.archiveFile) })
        exclude("META-INF/MANIFEST.MF")
        mappings.set(stubNamesFile(StubMappings.Format.TINY))
        this.libraries.from(libraries)
        remapper.from(configurations.detachedConfiguration(dependencies.create(TINY_REMAPPER)))
        manifest.attributes(attributes.toMap())
        archiveClassifier.set(classifier)
    }
}

/**
 * Real mode, a node with a stub: [real] — the toolchain's rename of [shadowTask] — against the same
 * rename run as a stub build runs it, over the same input. Joins `checkStubEquivalence`.
 */
fun Project.registerRenameEquivalence(shadowTask: String, real: TaskProvider<out AbstractArchiveTask>) {
    if (stubMode || !stubSignatureFile.isFile) return
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
        description = "The class files this node compiles against in stub mode, synthesized from stub.sig."
        signatures.set(stubSignatureFile)
        jar.set(layout.buildDirectory.file("stubs/stub.jar"))
    }

private const val RECORDER_ARG = "-Xplugin:CgSigRecorder"

/** Where [SigRecorder] and its service file are, for javac's processor path. */
private val recorderPath: List<File> by lazy {
    val loader = SigRecorder::class.java.classLoader
    val classes = File(SigRecorder::class.java.protectionDomain.codeSource.location.toURI())
    val service = loader.getResources("META-INF/services/com.sun.source.util.Plugin").toList()
        .firstOrNull { url -> url.openStream().use { it.readBytes().decodeToString() }.contains(SigRecorder::class.java.name) }
        ?: throw GradleException("singlejar-logic's jar carries no service file for ${SigRecorder::class.java.name}")
    val serviceRoot = when (service.protocol) {
        "jar" -> File((service.openConnection() as JarURLConnection).jarFileURL.toURI())
        else -> File(service.toURI()).parentFile.parentFile.parentFile
    }
    listOf(classes, serviceRoot).distinct()
}

private fun Project.recordReferences(compiles: List<String>) {
    for (name in compiles) {
        tasks.named(name, JavaCompile::class.java).configure {
            val record = layout.buildDirectory.file("stubs/records/$name.txt").get().asFile
            options.annotationProcessorPath = (options.annotationProcessorPath ?: files()) + files(recorderPath)
            options.compilerArgs.add("$RECORDER_ARG ${record.absolutePath}")
            outputs.file(record)
            // Something configured after this may replace the processor path; javac would then run without
            // the plugin and the stub be generated from a stale record.
            doFirst {
                if (options.annotationProcessorPath?.files?.containsAll(recorderPath) != true) {
                    throw GradleException("$path lost the reference recorder from its processor path")
                }
                record.delete()
            }
        }
    }
}
