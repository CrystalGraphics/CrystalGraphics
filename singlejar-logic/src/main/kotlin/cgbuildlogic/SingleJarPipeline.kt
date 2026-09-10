package cgbuildlogic

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar
import java.io.File
import java.util.jar.JarFile

/**
 * What one project's merged jar is made of. Everything here differs between projects; nothing in
 * {@link registerSingleJarPipeline} does.
 *
 * @property modId            the mod id, and the jar's `Automatic-Module-Name`
 * @property fileName         the finished artifact's name, e.g. `crystalgui-1.0.0.jar`
 * @property shadePath        where jvmdg's stubs land, as a PACKAGE PATH — `com/myproject/shadow`.
 *                            Never defaulted: jvmdg's default is the archive base name, and a
 *                            hyphenated one is not a legal package identifier, so the module system
 *                            rejects the jar before the early display
 * @property thinJars         `project path to task name` per loader. Named per loader because each
 *                            toolchain names its own production step
 * @property libraryProjects  compiled once, shipped once — the engine and everything under it
 * @property serviceOwners    projects whose `META-INF/services` are UNIONED rather than copied
 * @property relocations      `from package to to package`, applied to the merged tree
 * @property manifest         what each loader reads out of the one manifest
 * @property fabricThinJar    `project path to task name` of the Fabric thin jar, whose `Fabric-*`
 *                            attributes are copied verbatim; null when the project has no Fabric
 * @property descriptorsTask  the task emitting the per-loader descriptors
 * @property extraContent     anything else this project bundles — engine bands, native jars
 * @property configureCheck   this project's expectations of the finished jar
 */
data class SingleJarSpec(
    /**
     * Names this pipeline's tasks, its build directory and its libraries configuration — `single`
     * gives `singleJar`, `singleShadowJar`, `checkSingleJar`, `singleJarLibs`.
     *
     * <p>A project can register MORE THAN ONE: CrystalGUI ships its language stack as a second mod
     * from the same root build, and two pipelines under one name would collide on all seven tasks.
     * Lowercase, and a legal task-name prefix.</p>
     */
    val name: String = "single",

    /**
     * The Gradle group every task here lands in — its own folder in an IDE's task tree.
     *
     * <p>Not `build`: eight pipeline tasks scattered through the forty a modded build already
     * registers there is how `singleJar` becomes hard to find between `sourcesJar` and
     * `stageDevResources`. A group of their own also reads as one thing, which is what they are.</p>
     */
    val taskGroup: String = "$name jar",
    val modId: String,
    val fileName: String,
    val shadePath: String,
    val thinJars: List<Pair<String, String>>,
    val libraryProjects: List<String>,
    val serviceOwners: List<String>,
    val relocations: List<Pair<String, String>> = emptyList(),
    val manifest: Map<String, Any> = emptyMap(),
    val fabricThinJar: Pair<String, String>? = null,
    val descriptorsTask: String = "generateMergedDescriptors",
    val extraContent: ShadowJar.() -> Unit = {},
    val configureCheck: CheckSingleJar.() -> Unit = {},
)

/**
 * Registers the merge, the downgrade, the shade, the artifact and the check — the pipeline every
 * project on this build shares.
 *
 * <p>The order is <b>singleShadowJar → downgrade → shade → singleJar</b>, and it is not
 * interchangeable: jvmdg rewrites bytecode and adds references to its own stubs, so the stubs have to
 * be shaded in AFTER the rewrite. Remapping already happened per thin jar, which is the one inversion
 * from a fat chain and is safe because jvmdg does not read names.</p>
 *
 * <p>The caller keeps its own {@code plugins} block — a precompiled script plugin cannot receive one —
 * along with its group, version and jvmdg conventions, and declares its libraries into the
 * {@code singleJarLibs} configuration this creates.</p>
 *
 * <pre>{@code
 * registerSingleJarPipeline(SingleJarSpec(
 *     modId = "myproject",
 *     fileName = "myproject-1.0.0.jar",
 *     shadePath = "com/myproject/shadow",
 *     thinJars = listOf(":mc1710" to "reobfThinJar", ":mc1201:forge" to "reobfThinShadowJar"),
 *     libraryProjects = listOf(":core"),
 *     serviceOwners = listOf(":core"),
 * ))
 * dependencies { "singleJarLibs"("org.example:lib:1.0") }
 * }</pre>
 */
fun Project.registerSingleJarPipeline(spec: SingleJarSpec) {

    // Every task, directory and configuration below is named from spec.name, so a project can register
    // this pipeline more than once. @see SingleJarSpec.name
    val n = spec.name
    val N = n.replaceFirstChar { it.uppercase() }

    /**
     * Third-party libraries the merged jar carries. Created here, filled by the caller: a project's
     * libraries are its own, and the exclusions some of them need are not expressible as a list.
     */
    val singleJarLibs: Configuration = configurations.create("${n}JarLibs") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

    // ONE merged META-INF/services, unioned rather than copied. Two bundled modules that each ship a
    // file for the same service keep whichever arrived first and silently drop the other -- the jar
    // then carries some of its extensions and nothing reports the rest missing. Shadow's own
    // mergeServiceFiles does not help: these arrive through from(zipTree(...)) and the duplicate is
    // dropped before any transformer sees it.
    val servicesDir = layout.buildDirectory.dir("$n-jar/services").get().asFile
    val owners = spec.serviceOwners.map { project(it) }

    val mergeServices = tasks.register("merge${N}Services") {
        group = spec.taskGroup
        description = "Unions every bundled module's META-INF/services so none shadows another."
        val sources = owners.mapNotNull { owner ->
            owner.extensions.getByType(SourceSetContainer::class.java)["main"].output.resourcesDir
                ?.let { File(it, "META-INF/services") }
        }
        // THE FILES IT READS, or it compares on outputs alone and stays UP-TO-DATE across an edit to
        // one of them, leaving a merged copy that describes the previous build.
        inputs.files(sources).withPropertyName("serviceDirs").optional(true)
        outputs.dir(servicesDir)
        dependsOn(owners.map { "${it.path}:processResources" })
        val out = servicesDir
        doLast {
            val byService = linkedMapOf<String, MutableList<String>>()
            sources.filter { it.isDirectory }.forEach { directory ->
                directory.listFiles().orEmpty().forEach { file ->
                    val providers = byService.getOrPut(file.name) { mutableListOf() }
                    file.readLines().map { it.substringBefore('#').trim() }
                        .filter { it.isNotEmpty() && it !in providers }
                        .forEach { providers.add(it) }
                }
            }
            val dir = File(out, "META-INF/services")
            dir.mkdirs()
            dir.listFiles().orEmpty().forEach { it.delete() }
            byService.forEach { (service, providers) ->
                File(dir, service).writeText(buildString {
                    appendLine("# Unioned by merge${N}Services; see its declaration.")
                    providers.forEach { appendLine(it) }
                })
            }
        }
    }

    val singleShadowJar = tasks.register<ShadowJar>("${n}ShadowJar") {
        group = spec.taskGroup
        description = "Every loader and one engine in one jar, before downgrading."
        archiveClassifier.set("merged")
        // Nothing from a dependency configuration: every input is named explicitly below. `empty()`
        // rather than `= emptyList()`, because this is a lazy property and a plain Kotlin source file
        // has none of the assignment sugar a build script does.
        configurations.empty()
        destinationDirectory.set(layout.buildDirectory.dir("$n-jar"))

        spec.thinJars.forEach { (path, task) ->
            val jar = project(path).tasks.named<AbstractArchiveTask>(task)
            dependsOn(jar)
            from(jar.map { project.zipTree(it.archiveFile) }) { exclude("META-INF/services/**") }
        }
        spec.libraryProjects.forEach { path ->
            val jar = project(path).tasks.named<Jar>("jar")
            dependsOn(jar)
            from(jar.map { project.zipTree(it.archiveFile) }) { exclude("META-INF/services/**") }
        }
        from(mergeServices)

        singleJarLibs.forEach { from(project.zipTree(it)) }
        spec.relocations.forEach { (from, to) -> relocate(from, to) }

        val descriptors = tasks.named(spec.descriptorsTask)
        dependsOn(descriptors)
        from(descriptors)

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        if (spec.manifest.isNotEmpty()) manifest { attributes(spec.manifest) }

        // Loom's own attributes, copied from the Fabric thin jar rather than restated: they describe
        // how that jar was remapped -- eleven of them, measured -- and a Loom upgrade that adds a
        // twelfth is carried without an edit. The PREFIX is copied, never a list. `Multi-Release` is
        // deliberately not among them: it would put modern classes under META-INF/versions, where FML
        // 1.7.10's scanner reads them and calls the jar corrupt.
        spec.fabricThinJar?.let { (path, task) ->
            val fabricThin = project(path).tasks.named<AbstractArchiveTask>(task)
            doFirst {
                JarFile(fabricThin.get().archiveFile.get().asFile).use { jar ->
                    jar.manifest?.mainAttributes?.forEach { key, value ->
                        val name = key.toString()
                        if (name.startsWith("Fabric-")) manifest.attributes[name] = value.toString()
                    }
                }
            }
        }

        spec.extraContent(this)
    }

    val downgradeSingleJar = tasks.register<DowngradeJar>("downgrade${N}Jar") {
        group = spec.taskGroup
        description = "Rewrites every class in the merged jar to Java 8."
        dependsOn(singleShadowJar)
        inputFile.set(singleShadowJar.flatMap { it.archiveFile })
        downgradeTo.set(JavaVersion.VERSION_1_8)
        archiveClassifier.set("merged-java8")
        destinationDirectory.set(layout.buildDirectory.dir("$n-jar"))
    }

    val shadeSingleJar = tasks.register<ShadeJar>("shade${N}Jar") {
        group = spec.taskGroup
        description = "Bundles the jvmdg runtime stubs the downgrade now references."
        inputFile.set(downgradeSingleJar.flatMap { it.archiveFile })
        shadePath.set({ _: String -> spec.shadePath })
        archiveClassifier.set("merged-java8-shaded")
        destinationDirectory.set(layout.buildDirectory.dir("$n-jar"))
    }

    // The artifact. Unclassified, because it is the product rather than a stage of one. A Copy rather
    // than another Jar: the bytes are finished, and re-zipping them would change the hash for no
    // reason -- which a reproducibility check would then report as non-determinism.
    val singleJar = tasks.register<Copy>("${n}Jar") {
        group = spec.taskGroup
        description = "The one jar every loader installs."
        dependsOn(shadeSingleJar)
        from(shadeSingleJar.map { it.archiveFile })
        into(layout.buildDirectory.dir("libs"))
        val name = spec.fileName
        rename { name }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        // The same bytes twice, so a jar's hash means something: update detection, any bundled
        // manifest digests, and the reproducibility check all rest on it.
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    val checkSingleJar = tasks.register<CheckSingleJar>("check${N}Jar") {
        // Overrides the `verification` its own init sets: it belongs beside the pipeline it checks,
        // and a task type used by two pipelines cannot name either group itself.
        group = spec.taskGroup
        dependsOn(singleJar)
        jar.set(layout.buildDirectory.file("libs/${spec.fileName}"))
        classMajorCeiling.set(52)
        spec.configureCheck(this)
    }

    tasks.named("assemble") { dependsOn(singleJar) }
    tasks.register("check$N") {
        group = spec.taskGroup
        description = "Builds the single jar and asserts everything every loader needs of it."
        dependsOn(checkSingleJar)
    }
}
